package dev.pointtosky.mobile.ar.camera

import android.content.Context
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector

/**
 * CAM-2c physical-camera provenance experiment (`internalDebug` only, task §4). Real-Android glue
 * that turns a bound logical [CameraInfo] plus a requested physical Camera2 ID into a
 * [PhysicalCameraBindingResolution] - every actual verification decision is delegated to the pure
 * [verifyPhysicalCameraProvenance] (`PhysicalCameraProvenance.kt`); this file's only job is finding
 * the matching physical `CameraInfo` (via `CameraInfo.getPhysicalCameraInfos()`, CameraX >= 1.4.2)
 * and reading its characteristics (via the existing [Camera2CharacteristicsSource], reused unchanged -
 * a physical sub-camera's `CameraInfo` is still just a `CameraInfo`).
 */

/** `CameraSelector` for an explicit rear physical camera candidate (task §4's binding experiment). */
@OptIn(ExperimentalCamera2Interop::class)
internal fun explicitPhysicalCameraSelector(physicalCameraId: String): CameraSelector =
    CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .setPhysicalCameraId(physicalCameraId)
        .build()

/**
 * Given the [CameraInfo] CameraX handed back from a successful `bindToLifecycle` call using
 * [explicitPhysicalCameraSelector], determines — via the pure [selectPhysicalCameraInfoSource] — which
 * of two possible shapes CameraX returned, reads characteristics from the exact `CameraInfo` that
 * shape implies (never the logical camera's when a physical one is what's needed), and returns the
 * verified [PhysicalCameraBindingResolution]:
 *
 * - **Shape A**: [boundCameraInfo] is itself already backed by the requested physical camera
 *   (`Camera2CameraInfo.from(boundCameraInfo).cameraId == requestedPhysicalCameraId`) — characteristics
 *   are read directly from [boundCameraInfo], and [PhysicalCameraProvenance.logicalCameraId] is `null`
 *   (no separate logical `CameraInfo` was ever identified in this shape — never fabricated).
 * - **Shape B**: [boundCameraInfo] is the logical camera's own, and the requested ID is found among its
 *   `getPhysicalCameraInfos()` set — characteristics are read from that nested physical `CameraInfo`,
 *   and [PhysicalCameraProvenance.logicalCameraId] is [boundCameraInfo]'s own Camera2 ID (`null` only
 *   if that ID itself could not be read).
 *
 * A `CameraSelector`-level physical-camera bind can "succeed" (no exception) without CameraX actually
 * being able to attribute a physical `CameraInfo` to it on every device/HAL under *either* shape - this
 * function is exactly where that gap would surface, as
 * [PhysicalCameraBindingResolution.PhysicalCameraIdentityUnverified].
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun resolvePhysicalCameraBindingFromCameraInfo(
    boundCameraInfo: CameraInfo,
    requestedPhysicalCameraId: String,
    context: Context?,
): PhysicalCameraBindingResolution {
    val boundCamera2Id = runCatching { Camera2CameraInfo.from(boundCameraInfo).cameraId }.getOrNull()
    val declaredPhysicalCameraInfos = boundCameraInfo.physicalCameraInfos.toList()
    val declaredPhysicalCameraInfoIds =
        declaredPhysicalCameraInfos.mapNotNull { candidate -> runCatching { Camera2CameraInfo.from(candidate).cameraId }.getOrNull() }

    return when (
        selectPhysicalCameraInfoSource(
            boundCameraInfoCamera2Id = boundCamera2Id,
            requestedPhysicalCameraId = requestedPhysicalCameraId,
            declaredPhysicalCameraInfoIds = declaredPhysicalCameraInfoIds,
        )
    ) {
        PhysicalCameraInfoSelection.UseBoundCameraInfoDirectly -> {
            val snapshot = Camera2CharacteristicsSource(boundCameraInfo, context).readSnapshotOrNull()
            verifyPhysicalCameraProvenance(
                logicalCameraId = null,
                requestedPhysicalCameraId = requestedPhysicalCameraId,
                physicalCameraInfoFound = true,
                physicalCharacteristicsSnapshot = snapshot,
                bindingSource = PhysicalCameraBindingSource.BOUND_CAMERA_INFO_IS_PHYSICAL,
            )
        }
        PhysicalCameraInfoSelection.UseDeclaredPhysicalCameraInfo -> {
            val physicalCameraInfo =
                declaredPhysicalCameraInfos.firstOrNull { candidate ->
                    runCatching { Camera2CameraInfo.from(candidate).cameraId }.getOrNull() == requestedPhysicalCameraId
                }
            val snapshot = physicalCameraInfo?.let { Camera2CharacteristicsSource(it, context).readSnapshotOrNull() }
            verifyPhysicalCameraProvenance(
                logicalCameraId = boundCamera2Id,
                requestedPhysicalCameraId = requestedPhysicalCameraId,
                physicalCameraInfoFound = physicalCameraInfo != null,
                physicalCharacteristicsSnapshot = snapshot,
                bindingSource = PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
            )
        }
        PhysicalCameraInfoSelection.NoMatch -> PhysicalCameraBindingResolution.PhysicalCameraIdentityUnverified
    }
}

/**
 * Rear physical-camera candidates declared by [logicalCameraInfo] (task §4's "enumerate supported
 * physical camera candidates"), each candidate's own Camera2 ID as reported by
 * `CameraInfo.getPhysicalCameraInfos()` - never inferred from ID ordering (task explicitly forbids
 * this). Returns an empty list if [logicalCameraInfo] declares no physical cameras (a genuinely
 * single-sensor rear camera) or none could be identified.
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun declaredPhysicalCameraCandidates(logicalCameraInfo: CameraInfo): List<String> =
    logicalCameraInfo.physicalCameraInfos
        .mapNotNull { candidate -> runCatching { Camera2CameraInfo.from(candidate).cameraId }.getOrNull() }
        .sorted()
