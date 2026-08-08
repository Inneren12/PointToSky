package dev.pointtosky.core.astro.projection.camera.match

import dev.pointtosky.core.astro.projection.camera.CameraGeometryQuality
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel
import dev.pointtosky.core.astro.projection.camera.quality
import kotlin.math.atan

/**
 * The **angular scale** half of the matcher's input contract: how many radians one analysis-buffer pixel
 * is worth, where the optical axis sits, and how much that number can be trusted.
 *
 * ## Why this type has to exist
 * The SKY-2 detector deliberately does not know the camera's intrinsics — `StarDetector.kt`'s file KDoc
 * says so in its first paragraph, and that is the right call: a centroid is a fact about pixels and
 * needs no camera model. But a matcher built on geometric invariants needs a pixel↔angle scale, and it
 * must come from the same place production projection gets it: [CameraSessionGeometry]'s resolved
 * intrinsics over the analysis buffer. Without a carrier the matcher would have to reach for intrinsics
 * itself and would be free to derive them a second, subtly different way.
 *
 * ## No second copy of the projection math
 * Every number here is read straight off a [PinholeProjectionModel] — the same object
 * [dev.pointtosky.core.astro.projection.camera.prediction.projectStars] projects with. [pinhole] is
 * stored, not unpacked into independent fields, so "consistent with the production model" is true by
 * construction rather than by a test that has to keep two derivations in step. [forGeometry] is a
 * one-line delegation to [PinholeProjectionModel.forGeometry] and derives nothing of its own.
 *
 * ## Space and convention (consumed here, not chosen here)
 * All pixel quantities are **full analyzed-buffer** pixels — unrotated, uncropped — in the project's
 * continuous edge-coordinate convention (raster sample `[x, y]` is centred at `(x + 0.5, y + 0.5)`;
 * canonical statement in `PixelGeometry.kt`'s file KDoc and
 * `docs/camera_coordinate_calibration_contract.md` §9.2). That is the same space
 * [dev.pointtosky.core.astro.projection.camera.detect.DetectedSource]'s centroids and
 * `SkyPredictedStar.imageXPx`/`imageYPx` live in, which is what makes a detection-minus-prediction
 * residual a plain subtraction.
 *
 * ## What [quality] is for
 * [CameraGeometryQuality.LEGACY_INTRINSICS_FALLBACK] means the FOV these numbers come from is a
 * hardcoded default, not a measurement of the device in the user's hand — so the scale can be wrong by
 * a large, unknown factor. A matcher that scales its search tolerances by [radiansPerPixelXOnAxis] must
 * be able to see that, which is why the flag rides along with the numbers instead of being left behind
 * in the geometry bundle. This type does not decide what to do about it.
 *
 * ## What this deliberately is not
 * Not a projector and not an unprojector: there is no `unproject`, no ray, and no matcher math here.
 * A consumer that needs the exact angle between two arbitrary pixels computes it from [pinhole] itself;
 * [radiansPerPixelXOnAxis]/[radiansPerPixelYOnAxis] are the *on-axis* plate scale and nothing more (see
 * their own docs for how they fall off).
 *
 * The constructor stays public because there is no cross-field invariant to protect — a
 * [PinholeProjectionModel] carries no provenance, so no check here could tell a matching [quality] from
 * a mismatched one. [forGeometry] is nevertheless the only path production should use: it is the one
 * that reads both fields from the same geometry bundle.
 */
data class AnalysisBufferScale(
    val pinhole: PinholeProjectionModel,
    val quality: CameraGeometryQuality,
) {
    /** Horizontal focal length in analysis-buffer pixels. Finite and strictly positive, per [pinhole]. */
    val focalLengthXPx: Double get() = pinhole.focalLengthXPx

    /** Vertical focal length in analysis-buffer pixels. Finite and strictly positive, per [pinhole]. */
    val focalLengthYPx: Double get() = pinhole.focalLengthYPx

    /**
     * Optical-axis X in analysis-buffer pixels. Defaults to the buffer's exact geometric centre
     * (`imageWidthPx / 2`) whenever the device reports no measured principal point — which is always, as
     * of CAM-1b. `W / 2` is the centre only under the edge-coordinate convention above; under a
     * pixel-centre convention it would be `(W - 1) / 2`.
     */
    val principalPointXPx: Double get() = pinhole.principalPointXPx

    /** Optical-axis Y in analysis-buffer pixels; see [principalPointXPx]. */
    val principalPointYPx: Double get() = pinhole.principalPointYPx

    /** Full analyzed-buffer width in pixels. */
    val imageWidthPx: Double get() = pinhole.imageWidthPx

    /** Full analyzed-buffer height in pixels. */
    val imageHeightPx: Double get() = pinhole.imageHeightPx

    /**
     * Radians per pixel along X **at the optical axis** — the exact derivative `dθ/dx` of
     * `θ = atan(x / fx)` at `x = 0`, which is `1 / fx`.
     *
     * On-axis only, and it shrinks off-axis: at an offset of `x` pixels the local scale is
     * `fx / (fx² + x²)`, i.e. down by `cos²θ`. For a 66 deg horizontal FOV that is about a 30 % change
     * between the centre and the frame edge, so this number is a sizing aid for tolerances and search
     * radii, never a substitute for projecting through [pinhole] when an exact angle is needed.
     */
    val radiansPerPixelXOnAxis: Double get() = 1.0 / focalLengthXPx

    /** The Y analogue of [radiansPerPixelXOnAxis]: `1 / fy`, on-axis only, with the same falloff. */
    val radiansPerPixelYOnAxis: Double get() = 1.0 / focalLengthYPx

    /**
     * Full horizontal field of view of the analysis buffer, radians: `2·atan(W / (2·fx))`. This inverts
     * exactly the relation [PinholeProjectionModel.forGeometry] used to derive `fx` from the intrinsics'
     * `horizontalFovDeg`, so it round-trips to the FOV the camera reported — it is a convenience for a
     * caller sizing a catalog cone, not an independent measurement.
     */
    val horizontalFieldOfViewRad: Double get() = 2.0 * atan(imageWidthPx / (2.0 * focalLengthXPx))

    /** The vertical analogue of [horizontalFieldOfViewRad]: `2·atan(H / (2·fy))`. */
    val verticalFieldOfViewRad: Double get() = 2.0 * atan(imageHeightPx / (2.0 * focalLengthYPx))

    companion object {
        /**
         * Builds the scale carrier for one [geometry], reading the pinhole model from
         * [PinholeProjectionModel.forGeometry] and the calibration quality from the same
         * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution] that model was built
         * from. Derives nothing itself.
         *
         * Callers must gate on the intrinsics reference **first**, exactly as
         * [dev.pointtosky.core.astro.projection.camera.prediction.projectStars] does via its
         * `StarPredictionBatchResult.IntrinsicsMappingUnavailable` path: the underlying
         * [PinholeProjectionModel.forGeometry] throws for a physical-sensor-referenced or dimensionless
         * intrinsics value, and for an analysis-buffer reference whose recorded dimensions do not
         * exactly match this frame's buffer. This function does not soften that into a fallback — a
         * fabricated scale is worse than no scale, because a matcher cannot tell it is wrong.
         *
         * @throws IllegalArgumentException from [PinholeProjectionModel.forGeometry] when [geometry]'s
         *   intrinsics cannot be mapped onto this exact analysis buffer.
         */
        fun forGeometry(geometry: CameraSessionGeometry): AnalysisBufferScale =
            AnalysisBufferScale(
                pinhole = PinholeProjectionModel.forGeometry(geometry),
                quality = geometry.intrinsics.quality,
            )
    }
}
