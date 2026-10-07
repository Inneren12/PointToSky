package dev.pointtosky.core.astro.projection.camera.match

import dev.pointtosky.core.astro.projection.camera.CameraGeometryQuality
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.PixelPoint
import dev.pointtosky.core.astro.projection.camera.prediction.BufferOpticalCameraVector
import dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel
import dev.pointtosky.core.astro.projection.camera.quality
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * The **pixel↔ray geometry** half of the matcher's input contract: how to turn an analysis-buffer pixel
 * into an exact camera ray, how wide the image actually is in angle, and how much the numbers can be
 * trusted.
 *
 * ## Why this type has to exist
 * The SKY-2 detector deliberately does not know the camera's intrinsics — `StarDetector.kt`'s file KDoc
 * says so in its first paragraph, and that is the right call: a centroid is a fact about pixels and
 * needs no camera model. But a matcher built on angular invariants needs the exact ray for each detected
 * centroid, and it must come from the same place production projection gets it:
 * [CameraSessionGeometry]'s resolved intrinsics over the analysis buffer. Without a carrier the matcher
 * would have to reach for intrinsics itself and would be free to derive them a second, subtly different
 * way.
 *
 * ## The path a matcher is expected to take
 * ```text
 * detected pixel (DetectedSource.xPx/yPx)  ->  cameraRayFor(...)  ->  unit camera ray  ->
 * angleBetweenRad(...)  ->  angular invariant
 * ```
 * [cameraRayFor] is the **only** sanctioned pixel→ray step. It delegates to
 * [PinholeProjectionModel.unprojectToCameraRay], the single canonical inverse of the production forward
 * projection, which is what correctly applies the non-central principal point, `fx != fy`, and the
 * `axisSwapped`/`negateXInput`/`negateYInput` orientation flags. A consumer must not re-derive that
 * inverse: a second hand-written inversion is a second camera-coordinate contract, and the flags are
 * exactly the part that a from-the-outside re-derivation gets wrong.
 *
 * [angleBetweenRad] is the sanctioned ray→angle step for the same kind of reason: it is the function the
 * angular extents below are already measured with, and its `atan2` form stays numerically stable at the
 * fractions of a degree a matcher works at, where an open-coded `acos` of a dot product does not (see
 * its own KDoc). It takes **unit** rays — which is what [cameraRayFor] returns — and rejects anything
 * else rather than guessing.
 *
 * [radiansPerPixelXOnAxis]/[radiansPerPixelYOnAxis] are **not** part of that path. They are a
 * first-order, on-axis plate scale for sizing a search radius or a match tolerance, and they degrade
 * off-axis (see their own docs). Any angle that feeds a geometric invariant comes from rays, not from
 * multiplying a pixel distance by a plate scale.
 *
 * ## No second copy of the projection math
 * Every number here is read from, or computed through, one [PinholeProjectionModel] — the same object
 * [dev.pointtosky.core.astro.projection.camera.prediction.projectStars] projects with. [pinhole] is
 * stored, not unpacked into independent fields, so "consistent with the production model" is true by
 * construction rather than by a test that has to keep two derivations in step. [forGeometry] is a
 * one-line delegation to [PinholeProjectionModel.forGeometry] and derives nothing of its own, and the
 * angular extents below are measured between real unprojected rays rather than from a closed-form
 * formula that would have to special-case the orientation flags all over again.
 *
 * ## Space and convention (consumed here, not chosen here)
 * All pixel quantities are **full analyzed-buffer** pixels — unrotated, uncropped — in the project's
 * continuous edge-coordinate convention (raster sample `[x, y]` is centred at `(x + 0.5, y + 0.5)`;
 * canonical statement in `PixelGeometry.kt`'s file KDoc and
 * `docs/camera_coordinate_calibration_contract.md` §9.2). That is the same space
 * [dev.pointtosky.core.astro.projection.camera.detect.DetectedSource]'s centroids and
 * `SkyPredictedStar.imageXPx`/`imageYPx` live in, which is what makes a detection-minus-prediction
 * residual a plain subtraction. No display/viewport transform belongs anywhere in this type.
 *
 * ## What [quality] is for
 * [CameraGeometryQuality.LEGACY_INTRINSICS_FALLBACK] means the FOV these numbers come from is a
 * hardcoded default, not a measurement of the device in the user's hand — so the scale can be wrong by
 * a large, unknown factor. A matcher that scales its search tolerances by [radiansPerPixelXOnAxis] must
 * be able to see that, which is why the flag rides along with the numbers instead of being left behind
 * in the geometry bundle. This type does not decide what to do about it.
 *
 * ## What this deliberately is not
 * Not a matcher and not a projector: there is no association, no invariant, no descriptor, and no
 * forward projection here — [pinhole] owns the forward map and this type never wraps it.
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
     * (`imageWidthPx / 2`) whenever the device reports no measured principal point — which is the case
     * as of CAM-1b, but **not** something anything here may assume: a calibrated intrinsics value is
     * free to carry an off-centre axis, and every angular quantity below is derived from this field
     * rather than from `W / 2`. `W / 2` is the centre only under the edge-coordinate convention above;
     * under a pixel-centre convention it would be `(W - 1) / 2`.
     */
    val principalPointXPx: Double get() = pinhole.principalPointXPx

    /** Optical-axis Y in analysis-buffer pixels; see [principalPointXPx]. */
    val principalPointYPx: Double get() = pinhole.principalPointYPx

    /** Full analyzed-buffer width in pixels. */
    val imageWidthPx: Double get() = pinhole.imageWidthPx

    /** Full analyzed-buffer height in pixels. */
    val imageHeightPx: Double get() = pinhole.imageHeightPx

    /**
     * The exact unit camera ray for one analysis-buffer pixel — the matcher's pixel→ray step.
     *
     * A straight delegation to [PinholeProjectionModel.unprojectToCameraRay]; the inversion math lives
     * there, once, beside the forward projection it inverts. See the class KDoc for why a consumer must
     * not write its own.
     */
    fun cameraRayFor(point: PixelPoint): BufferOpticalCameraVector = pinhole.unprojectToCameraRay(point)

    /**
     * The ray at the optical axis — `cameraRayFor(principal point)`, i.e. `(0, 0, 1)`. Every angular
     * extent below is measured from this ray, so none of them assumes the axis is the raster centre.
     */
    val opticalAxisRay: BufferOpticalCameraVector
        get() = cameraRayFor(PixelPoint(principalPointXPx, principalPointYPx))

    /**
     * Angle from the optical axis to the image's **left** edge (`x = 0`), radians.
     *
     * Signed, and positive in the ordinary case where the axis lies inside the image: negative would
     * mean the optical axis sits left of the frame entirely, in which case the left edge is on the same
     * side as the right one and the signed sum is still the true horizontal extent. Equal to
     * `atan(cx / fx)` for an axis-aligned model, but computed as the angle between two real unprojected
     * rays so that [PinholeProjectionModel.axisSwapped] and the negation flags are handled by the one
     * canonical inverse rather than by a second formula here.
     */
    val leftAngularExtentRad: Double
        get() = signedExtentRad(PixelPoint(0.0, principalPointYPx), offsetPx = principalPointXPx)

    /** Angle from the optical axis to the image's **right** edge (`x = imageWidthPx`); see [leftAngularExtentRad]. */
    val rightAngularExtentRad: Double
        get() =
            signedExtentRad(
                PixelPoint(imageWidthPx, principalPointYPx),
                offsetPx = imageWidthPx - principalPointXPx,
            )

    /** Angle from the optical axis to the image's **top** edge (`y = 0`); see [leftAngularExtentRad]. */
    val topAngularExtentRad: Double
        get() = signedExtentRad(PixelPoint(principalPointXPx, 0.0), offsetPx = principalPointYPx)

    /** Angle from the optical axis to the image's **bottom** edge (`y = imageHeightPx`); see [leftAngularExtentRad]. */
    val bottomAngularExtentRad: Double
        get() =
            signedExtentRad(
                PixelPoint(principalPointXPx, imageHeightPx),
                offsetPx = imageHeightPx - principalPointYPx,
            )

    /**
     * Total horizontal angular extent of the analysis buffer, radians:
     * [leftAngularExtentRad] + [rightAngularExtentRad].
     *
     * **Not** `2·atan(W / (2·fx))`. That closed form is exact only when the principal point is the raster
     * centre, which a calibrated intrinsics value is under no obligation to be; it silently reports the
     * wrong extent for an off-centre axis, and it hides the asymmetry a caller may need. When the axis
     * *is* centred the two agree exactly, so nothing changes for the fallback path.
     *
     * This is the **full image** extent, not a cone radius. A candidate cone centred on the optical axis
     * needs a radius, not a width — see [enclosingConeRadiusRad].
     */
    val horizontalFieldOfViewRad: Double get() = leftAngularExtentRad + rightAngularExtentRad

    /** The vertical analogue of [horizontalFieldOfViewRad]: [topAngularExtentRad] + [bottomAngularExtentRad]. */
    val verticalFieldOfViewRad: Double get() = topAngularExtentRad + bottomAngularExtentRad

    /**
     * Radius of a cone, centred on the optical axis, that contains the whole analysis buffer: the
     * largest angle from [opticalAxisRay] to any of the four image corners.
     *
     * This is the number to size a catalog query with, because a [StarCatalogQuery] cone is specified by
     * a radius about one direction. Deriving that radius from half of [horizontalFieldOfViewRad] would
     * be wrong twice over: it ignores the vertical extent and the corners, and for an off-centre
     * principal point it is not even half the horizontal span in the worst direction.
     *
     * Conservative and axis-centred by construction, not minimal: for an off-centre axis the smallest
     * enclosing cone is centred somewhere else, so this over-covers rather than clipping the frame.
     * Over-covering costs a few extra candidates; under-covering silently drops stars that are visibly
     * in frame.
     */
    val enclosingConeRadiusRad: Double
        get() =
            listOf(
                PixelPoint(0.0, 0.0),
                PixelPoint(imageWidthPx, 0.0),
                PixelPoint(0.0, imageHeightPx),
                PixelPoint(imageWidthPx, imageHeightPx),
            ).maxOf { corner -> angleBetweenRad(opticalAxisRay, cameraRayFor(corner)) }

    /**
     * Radians per pixel along X **at the optical axis** — the exact derivative `dθ/dx` of
     * `θ = atan(x / fx)` at `x = 0`, which is `1 / fx`.
     *
     * A sizing aid for tolerances and search radii, and nothing else. It is on-axis only and shrinks
     * off-axis: at an offset of `x` pixels the local scale is `fx / (fx² + x²)`, i.e. down by `cos²θ`.
     * For a 66 deg horizontal FOV that is about a 30 % change between the centre and the frame edge. An
     * angle that feeds a geometric invariant must come from [cameraRayFor], never from this.
     */
    val radiansPerPixelXOnAxis: Double get() = 1.0 / focalLengthXPx

    /** The Y analogue of [radiansPerPixelXOnAxis]: `1 / fy`, on-axis only, with the same falloff. */
    val radiansPerPixelYOnAxis: Double get() = 1.0 / focalLengthYPx

    /**
     * The angle from [opticalAxisRay] to the ray at [edge], signed by which side of the axis [edge] sits
     * on ([offsetPx] is the pixel distance from the axis to that edge along the relevant image axis,
     * positive when the edge is on the far side from the axis in the usual inside-the-image case).
     *
     * The magnitude comes from real rays so the orientation flags are applied exactly once, by the
     * canonical inverse; only the sign is read off the pixel geometry, where it is unambiguous.
     */
    private fun signedExtentRad(
        edge: PixelPoint,
        offsetPx: Double,
    ): Double {
        val magnitude = angleBetweenRad(opticalAxisRay, cameraRayFor(edge))
        return if (offsetPx >= 0.0) magnitude else -magnitude
    }

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

/**
 * The unsigned angle between two **unit** camera rays, radians, in `[0, π]` — the matcher's ray→angle
 * step.
 *
 * ## What the arguments must be
 * [a] and [b] are finite unit rays, normally obtained from [AnalysisBufferScale.cameraRayFor], and that
 * is **enforced**, not merely documented: each argument's squared norm must sit within `1e-6` of `1.0`
 * (`UNIT_RAY_NORM_SQUARED_TOLERANCE`, whose KDoc justifies the value) or the call throws.
 * [BufferOpticalCameraVector] itself
 * guarantees only that its components are finite, so without this check `angleBetweenRad(zero, ray)`
 * would evaluate `atan2(0, 0)` and hand back `0.0` — an angle, from a vector that has no direction —
 * and a vector with `1e300`-scale components would overflow the intermediate products into `Infinity`
 * before `atan2` ever saw them. Both are rejected here instead.
 *
 * Nothing is silently normalized. Along the sanctioned path every ray is already unit, so a non-unit
 * argument means the geometry was assembled wrong somewhere upstream; normalizing it would make that
 * bug produce a plausible angle instead of a stack trace. A caller holding a ray from the *prediction*
 * chain ([dev.pointtosky.core.astro.projection.camera.prediction.worldToDeviceVector] onward, which
 * never renormalizes and inherits the attitude matrix's orthonormality error) must normalize it itself,
 * deliberately, before calling.
 *
 * The result is the angle between the two *directions*: symmetric in its arguments and zero for a ray
 * against itself, and unsigned — which of the two rays is "first" is not a fact this can report, and a
 * matcher that needs an orientation has to get it from the geometry it is fitting, not from here.
 *
 * ## Why `atan2`, not `acos`
 * `atan2(|a × b|, a · b)` rather than `acos(a · b)`: for the small angles this is mostly used at — an
 * image edge a few degrees off axis, two stars a fraction of a degree apart — `acos` loses roughly half
 * its significant digits, because its argument sits on the flat part of the cosine near 1. The `atan2`
 * form is far better conditioned across the whole range (it is still floating-point arithmetic, not an
 * exact answer) and needs no clamping to survive a dot product that floating point nudged just past 1.
 * `CameraRayAngleTest` measures the gap against an analytic reference: at a sixty-fourth of a pixel of
 * separation `acos(a · b)` is already wrong in its eighth significant digit, and at ten nanoradians it
 * collapses to exactly zero, while this form matches the reference to within the tested tolerance at
 * both.
 *
 * Public **so that there is exactly one of it**. The angular extents on [AnalysisBufferScale] are
 * measured with this function, and a consumer computing an angular invariant calls the same one rather
 * than open-coding `acos` of a dot product a second time — which is the version that quietly loses the
 * precision an invariant is built out of. Still not a matcher utility beyond that: no association, no
 * invariant, no descriptor lives here.
 *
 * @throws IllegalArgumentException if either argument is not a unit ray — the zero vector, a vector
 *   whose components are finite but whose squared norm overflows or underflows, or any other
 *   measurably non-unit vector.
 */
fun angleBetweenRad(
    a: BufferOpticalCameraVector,
    b: BufferOpticalCameraVector,
): Double {
    requireUnitRay(a, "a")
    requireUnitRay(b, "b")

    val crossX = a.y * b.z - a.z * b.y
    val crossY = a.z * b.x - a.x * b.z
    val crossZ = a.x * b.y - a.y * b.x
    val crossMagnitude = sqrt(crossX * crossX + crossY * crossY + crossZ * crossZ)
    val dot = a.x * b.x + a.y * b.y + a.z * b.z
    return atan2(crossMagnitude, dot)
}

/**
 * How far a ray's **squared** norm may sit from `1.0` before [angleBetweenRad] refuses it.
 *
 * Squared rather than the norm itself so the check costs no `sqrt`; near `1.0` the two differ only by a
 * factor of two, which is well inside the margin this value is chosen with.
 *
 * `1e-6` is generous against the producers it has to accept and decisive against everything it has to
 * reject. [PinholeProjectionModel.unprojectToCameraRay] divides by a `sqrt` in `Double` and lands within
 * two ulps — `CameraRayAngleTest` measures `4.5e-16` as the worst case over the buffer, on and off axis,
 * with the orientation flags set — and unit vectors built from `sin`/`cos` or `1/sqrt(3)` land within
 * one. That leaves ten orders of magnitude of headroom, while `(2, 0, 0)`, the zero vector, and any
 * vector whose squared norm overflows to `Infinity` or underflows to `0.0` are all far outside it. It is
 * deliberately much tighter than `SkySessionLog.kt`'s `ROTATION_MATRIX_ORTHONORMAL_TOLERANCE` (`1e-3`),
 * which is loose because it has to tolerate `Float`-derived sensor attitude: nothing on this function's
 * contract comes from that path.
 */
private const val UNIT_RAY_NORM_SQUARED_TOLERANCE: Double = 1e-6

/**
 * Refuses [ray] unless it is a unit vector, naming [argumentName] so the caller can tell which of
 * [angleBetweenRad]'s two arguments was malformed.
 *
 * The finiteness check is not redundant with [BufferOpticalCameraVector]'s own: its components can each
 * be finite while their squares overflow, and `Infinity` reaching `atan2` would surface as a quiet `0.0`
 * or `NaN` rather than as the contract violation it is.
 */
private fun requireUnitRay(
    ray: BufferOpticalCameraVector,
    argumentName: String,
) {
    val normSquared = ray.x * ray.x + ray.y * ray.y + ray.z * ray.z
    require(normSquared.isFinite() && abs(normSquared - 1.0) <= UNIT_RAY_NORM_SQUARED_TOLERANCE) {
        "angleBetweenRad requires unit rays (normally from AnalysisBufferScale.cameraRayFor); " +
            "argument $argumentName had squared norm $normSquared, which is not within " +
            "$UNIT_RAY_NORM_SQUARED_TOLERANCE of 1.0; was $ray"
    }
}
