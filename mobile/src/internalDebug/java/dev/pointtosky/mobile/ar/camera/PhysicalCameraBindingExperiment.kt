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
 * [explicitPhysicalCameraSelector], finds the physical `CameraInfo` matching [requestedPhysicalCameraId]
 * in `boundLogicalCameraInfo.physicalCameraInfos`, reads its characteristics directly (never the
 * logical camera's), and returns the verified [PhysicalCameraBindingResolution].
 *
 * A `CameraSelector`-level physical-camera bind can "succeed" (no exception) without CameraX actually
 * being able to attribute a physical `CameraInfo` to it on every device/HAL - this function is exactly
 * where that gap would surface, as [PhysicalCameraBindingResolution.PhysicalCameraIdentityUnverified].
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun resolvePhysicalCameraBindingFromCameraInfo(
    boundLogicalCameraInfo: CameraInfo,
    requestedPhysicalCameraId: String,
    context: Context?,
): PhysicalCameraBindingResolution {
    val logicalCameraId =
        runCatching { Camera2CameraInfo.from(boundLogicalCameraInfo).cameraId }.getOrNull()
    val physicalCameraInfo =
        boundLogicalCameraInfo.physicalCameraInfos.firstOrNull { candidate ->
            runCatching { Camera2CameraInfo.from(candidate).cameraId }.getOrNull() == requestedPhysicalCameraId
        }
    val physicalSnapshot =
        physicalCameraInfo?.let { Camera2CharacteristicsSource(it, context).readSnapshotOrNull() }
    return verifyPhysicalCameraProvenance(
        logicalCameraId = logicalCameraId,
        requestedPhysicalCameraId = requestedPhysicalCameraId,
        physicalCameraInfoFound = physicalCameraInfo != null,
        physicalCharacteristicsSnapshot = physicalSnapshot,
    )
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
