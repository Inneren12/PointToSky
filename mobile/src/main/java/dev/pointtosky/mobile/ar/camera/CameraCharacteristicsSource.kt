package dev.pointtosky.mobile.ar.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import kotlinx.coroutines.CancellationException

/**
 * Thin snapshot of the subset of Camera2 `CameraCharacteristics` [CameraIntrinsicsResolver]/
 * [resolveAnalysisBufferIntrinsics] need (CAM-1b, extended CAM-2c), decoupled from
 * `CameraCharacteristics.Key` mechanics so resolution logic can be unit tested with plain fakes — no
 * real camera, no Robolectric.
 *
 * All fields describe the **one, actual bound camera** ([Camera2CharacteristicsSource]'s own
 * `cameraInfo`) — never another physical camera in a logical multi-camera device (CAM-2c §1).
 * [isLogicalMultiCamera] lets [resolveAnalysisBufferIntrinsics] refuse to build a calibrated
 * mapping at all in that case, rather than risk silently mixing metadata from a physical camera
 * other than the one actually producing the analyzed frame (see that function's KDoc).
 *
 * @property availableFocalLengthsMm raw `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`, millimetres.
 * @property sensorPhysicalWidthMm raw `SENSOR_INFO_PHYSICAL_SIZE` width, millimetres.
 * @property sensorPhysicalHeightMm raw `SENSOR_INFO_PHYSICAL_SIZE` height, millimetres.
 * @property activeArrayLeftPx raw `SENSOR_INFO_ACTIVE_ARRAY_SIZE.left`, pixels.
 * @property activeArrayTopPx raw `SENSOR_INFO_ACTIVE_ARRAY_SIZE.top`, pixels.
 * @property activeArrayRightPx raw `SENSOR_INFO_ACTIVE_ARRAY_SIZE.right`, pixels.
 * @property activeArrayBottomPx raw `SENSOR_INFO_ACTIVE_ARRAY_SIZE.bottom`, pixels.
 * @property pixelArrayWidthPx raw `SENSOR_INFO_PIXEL_ARRAY_SIZE` width, pixels (CAM-2c fix §P2) — the
 *   full sensor pixel grid `SENSOR_INFO_PHYSICAL_SIZE`'s physical millimetres actually span, distinct
 *   from [activeArrayRightPx]`-`[activeArrayLeftPx]: the active array may exclude optically black or
 *   otherwise inactive border pixels, so it can be smaller than the full pixel array. Required only
 *   for the focal-length-derived fallback path (see
 *   `dev.pointtosky.core.astro.projection.camera.activeArrayIntrinsicsFromFocalLength`'s KDoc) —
 *   never for a usable `LENS_INTRINSIC_CALIBRATION`, whose `fx`/`fy` are already supplied directly in
 *   pixels. `null` when the device does not report `SENSOR_INFO_PIXEL_ARRAY_SIZE` (mandatory on real
 *   Camera2 devices, but this snapshot never assumes a key is present).
 * @property pixelArrayHeightPx raw `SENSOR_INFO_PIXEL_ARRAY_SIZE` height, pixels.
 * @property preCorrectionActiveArrayLeftPx raw `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE.left`,
 *   pixels, when the device reports one (only present on devices with geometric distortion
 *   correction — see [resolveAnalysisBufferIntrinsics]'s KDoc on why this must be checked before
 *   trusting [lensIntrinsicCalibration]'s coordinate space).
 * @property preCorrectionActiveArrayTopPx raw `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE.top`.
 * @property preCorrectionActiveArrayRightPx raw `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE.right`.
 * @property preCorrectionActiveArrayBottomPx raw `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE.bottom`.
 * @property lensIntrinsicCalibration raw `LENS_INTRINSIC_CALIBRATION` — `[fx, fy, cx, cy, skew]` in
 *   `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE` pixel coordinates, when the device reports it.
 * @property lensDistortion raw `LENS_DISTORTION` — radial/tangential distortion coefficients.
 *   Resolved and recorded only (CAM-2c §1); never applied to the linear pinhole model
 *   [dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel] carries no
 *   distortion terms, and adding them is out of CAM-2c's scope.
 * @property isLogicalMultiCamera true when this camera's `REQUEST_AVAILABLE_CAPABILITIES` includes
 *   `REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA` — i.e. this camera ID's characteristics
 *   may not correspond 1:1 to whichever physical sensor actually produced a given analyzed frame.
 * @property cameraId (CAM-2c fix §5, "Option C") the bound Camera2 ID this snapshot describes —
 *   always available through CameraX interop (`Camera2CameraInfo.getCameraId()`), regardless of
 *   whether [context] was supplied. Diagnostics only; never used to select or rebind a camera.
 * @property physicalCameraIds (CAM-2c fix §5, "Option C") the full set of physical camera IDs
 *   [isLogicalMultiCamera] declares this logical camera is composed of, when [context] was supplied
 *   and the raw `android.hardware.camera2.CameraManager.getCameraCharacteristics(cameraId)
 *   .getPhysicalCameraIds()` read succeeded. `null` when [context] was not supplied, the read failed,
 *   or the running API level cannot report it (API 28+ only) — **not** the same as an empty set
 *   (which would mean "queried successfully; this device declares zero physical children", a
 *   contradiction for a true logical camera, but a valid answer for a non-logical one). This
 *   codebase's pinned `androidx.camera:camera-camera2:1.3.4` exposes no CameraX-native equivalent —
 *   `CameraInfo.getPhysicalCameraInfos()` only arrived in `1.4.0-beta01` — so this is a deliberate,
 *   narrow, read-only bypass through the platform API directly, never used to bind or select a
 *   physical camera (see [resolveAnalysisBufferIntrinsics]'s KDoc on why CAM-2c still refuses to
 *   build a calibrated mapping for any logical camera regardless of what this reveals).
 */
data class CameraCharacteristicsSnapshot(
    val availableFocalLengthsMm: FloatArray?,
    val sensorPhysicalWidthMm: Float?,
    val sensorPhysicalHeightMm: Float?,
    val activeArrayLeftPx: Int? = null,
    val activeArrayTopPx: Int? = null,
    val activeArrayRightPx: Int? = null,
    val activeArrayBottomPx: Int? = null,
    val pixelArrayWidthPx: Int? = null,
    val pixelArrayHeightPx: Int? = null,
    val preCorrectionActiveArrayLeftPx: Int? = null,
    val preCorrectionActiveArrayTopPx: Int? = null,
    val preCorrectionActiveArrayRightPx: Int? = null,
    val preCorrectionActiveArrayBottomPx: Int? = null,
    val lensIntrinsicCalibration: FloatArray? = null,
    val lensDistortion: FloatArray? = null,
    val isLogicalMultiCamera: Boolean = false,
    val cameraId: String? = null,
    val physicalCameraIds: Set<String>? = null,
)

/**
 * Reads a [CameraCharacteristicsSnapshot] for one camera. Implementations may throw if the
 * underlying metadata is inaccessible; [CameraIntrinsicsResolver] treats any thrown exception as
 * "characteristics unavailable" and falls back to [dev.pointtosky.core.astro.projection.camera.legacyFallbackCameraIntrinsics].
 */
fun interface CameraCharacteristicsSource {
    fun snapshot(): CameraCharacteristicsSnapshot
}

/**
 * Reads this [CameraCharacteristicsSource] exactly once, converting a thrown exception into `null`
 * (CAM-2c runtime integration fix P1) — the single, one-read-per-call boundary every caller in this
 * package (`resolveAnalysisBufferIntrinsics`, `resolveCameraIntrinsics`,
 * `resolveCameraIntrinsicsPreferringCalibration`) is built on, so a single uncached session
 * resolution never reads [source] more than once even when it is consulted by more than one
 * resolver. `CancellationException` is rethrown, never converted to `null` or swallowed — a
 * cancelled coroutine must not silently resolve to "characteristics unavailable".
 *
 * Callers that need to distinguish "characteristics unavailable" from other failure reasons must do
 * so from the resulting `null`, exactly as before this fix — this function itself carries no
 * additional retry or fallback behavior beyond the one read.
 */
internal fun CameraCharacteristicsSource.readSnapshotOrNull(): CameraCharacteristicsSnapshot? =
    try {
        snapshot()
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

/**
 * Production [CameraCharacteristicsSource] backed by CameraX's Camera2 interop
 * (`Camera2CameraInfo.from(cameraInfo)`).
 *
 * [cameraInfo] is the exact bound camera whose characteristics this reads — CAM-2c §1's "use the
 * actual selected rear camera ID/lens" requirement is satisfied by construction: this class never
 * reads from any other `CameraInfo`/camera ID, and never substitutes a physical camera's
 * characteristics for a logical camera's (or vice versa) — see [CameraCharacteristicsSnapshot.isLogicalMultiCamera]
 * for the one case ([resolveAnalysisBufferIntrinsics]) where the metadata's own applicability to a
 * specific captured frame cannot be guaranteed regardless.
 *
 * [ExperimentalCamera2Interop] is AndroidX's legacy Java-based `@RequiresOptIn`-style experimental
 * marker (`androidx.annotation.RequiresOptIn`), not Kotlin's native one — so it must be suppressed
 * with `androidx.annotation.OptIn`, not `kotlin.OptIn`. The two have identical call syntax
 * (`@OptIn(ExperimentalCamera2Interop::class)`), so this is easy to get wrong silently:
 * `kotlin.OptIn` compiles cleanly and even passes plain JVM unit tests, but Android Lint's
 * `UnsafeOptInUsageError` check (which enforces this marker, not the Kotlin compiler) only
 * recognizes `androidx.annotation.OptIn` and fails `:mobile:lintInternalDebug` if it is missing.
 *
 * [context], when supplied, is used **only** for [CameraCharacteristicsSnapshot.physicalCameraIds]'s
 * read-only diagnostic lookup (CAM-2c fix §5) — see that property's KDoc. `null` (safe default) simply
 * omits that one field; every other snapshot field and this codebase's actual gating behavior
 * ([CameraCharacteristicsSnapshot.isLogicalMultiCamera]) are entirely unaffected.
 */
internal class Camera2CharacteristicsSource(
    private val cameraInfo: CameraInfo,
    private val context: Context? = null,
) : CameraCharacteristicsSource {
    @OptIn(ExperimentalCamera2Interop::class)
    override fun snapshot(): CameraCharacteristicsSnapshot {
        val characteristics = Camera2CameraInfo.from(cameraInfo)
        val physicalSize = characteristics.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val activeArray = characteristics.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val pixelArraySize = characteristics.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
        val preCorrectionActiveArray =
            characteristics.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        val capabilities = characteristics.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val cameraId = characteristics.cameraId

        return CameraCharacteristicsSnapshot(
            availableFocalLengthsMm =
                characteristics.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS),
            sensorPhysicalWidthMm = physicalSize?.width,
            sensorPhysicalHeightMm = physicalSize?.height,
            activeArrayLeftPx = activeArray?.left,
            activeArrayTopPx = activeArray?.top,
            activeArrayRightPx = activeArray?.right,
            activeArrayBottomPx = activeArray?.bottom,
            pixelArrayWidthPx = pixelArraySize?.width,
            pixelArrayHeightPx = pixelArraySize?.height,
            preCorrectionActiveArrayLeftPx = preCorrectionActiveArray?.left,
            preCorrectionActiveArrayTopPx = preCorrectionActiveArray?.top,
            preCorrectionActiveArrayRightPx = preCorrectionActiveArray?.right,
            preCorrectionActiveArrayBottomPx = preCorrectionActiveArray?.bottom,
            lensIntrinsicCalibration = characteristics.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION),
            // LENS_DISTORTION (replacing the deprecated LENS_RADIAL_DISTORTION) requires API 28;
            // this project's minSdk is 26, so it must be read only on API 28+ - never on an older
            // device, where the key does not exist at all.
            lensDistortion =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    characteristics.getCameraCharacteristic(CameraCharacteristics.LENS_DISTORTION)
                } else {
                    null
                },
            // REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA is itself an API-28+ constant
            // (`LOGICAL_MULTI_CAMERA` support was formalized in Android P); guarded the same way as
            // LENS_DISTORTION above, even though the int literal is safe to inline on any API level -
            // no pre-28 device's capabilities array can ever contain it regardless.
            isLogicalMultiCamera =
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                    capabilities?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) == true,
            cameraId = cameraId,
            physicalCameraIds = readPhysicalCameraIds(cameraId),
        )
    }

    /**
     * (CAM-2c fix §5, "Option C") Read-only, diagnostics-only bypass through the raw platform
     * `CameraManager` — never used to bind, select, or reconfigure any camera; only to report, for
     * the diagnostics panel, which physical camera IDs the *already-bound* logical camera declares.
     * `null` whenever [context] is absent, the read fails (`CameraAccessException` or any other
     * exception — e.g. a permission or HAL quirk on some device), or the running API level cannot
     * report it (`CameraCharacteristics.getPhysicalCameraIds()` requires API 28).
     */
    private fun readPhysicalCameraIds(cameraId: String): Set<String>? {
        val appContext = context ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return try {
            val cameraManager = appContext.getSystemService(CameraManager::class.java) ?: return null
            cameraManager.getCameraCharacteristics(cameraId).physicalCameraIds
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
}
