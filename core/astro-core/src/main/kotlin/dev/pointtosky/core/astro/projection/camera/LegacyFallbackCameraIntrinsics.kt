package dev.pointtosky.core.astro.projection.camera

import dev.pointtosky.core.astro.projection.VERTICAL_FOV_DEG
import kotlin.math.atan
import kotlin.math.tan

/**
 * Builds the explicit legacy-fallback [CameraIntrinsics] the `:mobile` resolver returns when no
 * usable real per-device metadata can be resolved (CAM-1b §4).
 *
 * Vertical FOV is [VERTICAL_FOV_DEG] — the exact same constant the AR overlay's
 * `projectionParams(viewport)` still uses today (`internal`, same `:core:astro-core` module), so
 * there is a single source of truth for the "legacy 56°" assumption.
 *
 * Horizontal FOV mirrors how the legacy overlay already derives it: from the aspect ratio of the
 * frame being projected (`tanHFov = tanVFov * aspect`), NOT from any physical sensor guess. When
 * the analyzed image dimensions are known, this reproduces that aspect-derived value exactly. When
 * they are not known yet, horizontal FOV falls back to the vertical FOV (an explicit square-aspect
 * policy, documented here — never a guess about physical lens geometry).
 *
 * @param imageWidthPx analyzed-image width in pixels, if known.
 * @param imageHeightPx analyzed-image height in pixels, if known.
 */
fun legacyFallbackCameraIntrinsics(
    imageWidthPx: Int? = null,
    imageHeightPx: Int? = null,
): CameraIntrinsics {
    val verticalFov = VERTICAL_FOV_DEG
    val horizontalFov =
        if (imageWidthPx != null && imageHeightPx != null && imageWidthPx > 0 && imageHeightPx > 0) {
            val aspect = imageWidthPx.toDouble() / imageHeightPx.toDouble()
            val tanVFov = tan(Math.toRadians(verticalFov / 2.0))
            Math.toDegrees(2.0 * atan(tanVFov * aspect))
        } else {
            verticalFov
        }

    return CameraIntrinsics(
        horizontalFovDeg = horizontalFov,
        verticalFovDeg = verticalFov,
        focalLengthMm = null,
        sensorWidthMm = null,
        sensorHeightMm = null,
        principalPointXPx = null,
        principalPointYPx = null,
        source = CameraIntrinsicsSource.LEGACY_FALLBACK,
    )
}
