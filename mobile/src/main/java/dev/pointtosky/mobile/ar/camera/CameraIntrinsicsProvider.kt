package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.CameraInfo
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics

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
 */
data class CameraIntrinsicsResolution(
    val intrinsics: CameraIntrinsics,
    val fallbackReason: String? = null,
)

/**
 * Resolves real per-device [CameraIntrinsics] for a bound CameraX camera (CAM-1b).
 *
 * This provider has no production call sites yet: the AR renderer still calls the legacy
 * `projectionParams(viewport)` (see `dev.pointtosky.core.astro.projection.projectionParams`) and
 * this PR does not change that. This slice establishes and tests the intrinsics contract only.
 */
interface CameraIntrinsicsProvider {
    fun resolve(
        cameraInfo: CameraInfo,
        imageWidthPx: Int?,
        imageHeightPx: Int?,
    ): CameraIntrinsicsResolution
}

/**
 * Production [CameraIntrinsicsProvider] backed by Camera2 interop
 * ([Camera2CharacteristicsSource]). Resolution logic itself lives in [resolveCameraIntrinsics],
 * which takes a [CameraCharacteristicsSource] so it can be unit-tested without a real camera.
 */
class Camera2CameraIntrinsicsProvider : CameraIntrinsicsProvider {
    override fun resolve(
        cameraInfo: CameraInfo,
        imageWidthPx: Int?,
        imageHeightPx: Int?,
    ): CameraIntrinsicsResolution =
        resolveCameraIntrinsics(Camera2CharacteristicsSource(cameraInfo), imageWidthPx, imageHeightPx)
}
