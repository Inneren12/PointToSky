package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.CameraInfo
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransform

/**
 * Result of resolving [CameraIntrinsics] for a bound camera (CAM-1b).
 *
 * @property intrinsics the resolved value — either real per-device metadata
 *   ([dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource.CAMERA_CHARACTERISTICS])
 *   or the explicit legacy fallback
 *   ([dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource.LEGACY_FALLBACK]).
 * @property fallbackReason `null` when [intrinsics] came from real metadata; a short, non-sensitive
 *   diagnostic code (see [CameraIntrinsicsFallbackReason]) explaining why the legacy fallback was
 *   used otherwise.
 * @property calibrationDiagnostics (CAM-2c §9) non-`null` only when [intrinsics] came from a
 *   successful [resolveAnalysisBufferIntrinsics] mapping — every intermediate quantity that
 *   resolution computed, for the internal debug panel only. Never read by projection itself.
 */
data class CameraIntrinsicsResolution(
    val intrinsics: CameraIntrinsics,
    val fallbackReason: String? = null,
    val calibrationDiagnostics: CameraCalibrationDiagnostics? = null,
)

/**
 * Resolves real per-device [CameraIntrinsics] for a bound CameraX camera (CAM-1b; CAM-2c adds the
 * calibrated `AnalysisBuffer` mapping — see [resolveCameraIntrinsicsPreferringCalibration]).
 *
 * @param sensorToBufferTransform (CAM-2c) the real per-frame sensor-to-buffer mapping for the exact
 *   frame [imageWidthPx]/[imageHeightPx] describe, when known — see
 *   [dev.pointtosky.mobile.ar.camera.ImageProxyFrameMetadataSource]. `null` when unavailable; the
 *   calibrated path is then skipped in favor of the CAM-1b path, never fabricated.
 */
interface CameraIntrinsicsProvider {
    fun resolve(
        cameraInfo: CameraInfo,
        imageWidthPx: Int?,
        imageHeightPx: Int?,
        sensorToBufferTransform: SensorToBufferTransform? = null,
    ): CameraIntrinsicsResolution
}

/**
 * Production [CameraIntrinsicsProvider] backed by Camera2 interop
 * ([Camera2CharacteristicsSource]). Resolution logic itself lives in
 * [resolveCameraIntrinsicsPreferringCalibration] (CAM-2c), which tries the calibrated
 * `AnalysisBuffer` mapping ([resolveAnalysisBufferIntrinsics]) before falling back to the unchanged
 * CAM-1b [resolveCameraIntrinsics] — both take a [CameraCharacteristicsSource] so they can be
 * unit-tested without a real camera.
 */
class Camera2CameraIntrinsicsProvider : CameraIntrinsicsProvider {
    override fun resolve(
        cameraInfo: CameraInfo,
        imageWidthPx: Int?,
        imageHeightPx: Int?,
        sensorToBufferTransform: SensorToBufferTransform?,
    ): CameraIntrinsicsResolution =
        resolveCameraIntrinsicsPreferringCalibration(
            Camera2CharacteristicsSource(cameraInfo),
            sensorToBufferTransform,
            imageWidthPx,
            imageHeightPx,
        )
}
