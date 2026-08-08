package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.PixelPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * A validated pinhole-camera projection model: pixel-space focal lengths and principal point over
 * one image coordinate space (CAM-2a §7).
 *
 * ## Which coordinate space?
 * [imageWidthPx]/[imageHeightPx] (and therefore [principalPointXPx]/[principalPointYPx] and the
 * pixel coordinates [project] returns) are in the **full analyzed-buffer** space: `frame.bufferWidthPx
 * ×frame.bufferHeightPx`, *unrotated* and *uncropped* — exactly
 * [dev.pointtosky.core.astro.projection.camera.CropScaleTransform.imageToDisplay]'s documented input
 * ("buffer space"), and exactly what
 * [dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata] itself documents
 * (`bufferWidthPx`/`bufferHeightPx` "are not swapped to account for rotationDegrees, and are not
 * assumed to equal any display/viewport size"). This is CAM-2a's chosen "Model A": project directly
 * into unrotated source-buffer coordinates, then let [CropScaleTransform.imageToDisplay]'s existing,
 * already-tested rotate→scale→offset pipeline carry the point the rest of the way to a display pixel.
 * The pinhole model here applies **no** rotation of its own — applying [forGeometry]'s
 * `frame.rotationDegrees` again here, on top of what `imageToDisplay` already does, would rotate the
 * point twice.
 *
 * ## `axisSwapped`/`negateXInput`/`negateYInput`: a second, independent position-space remapping
 * (CAM-2c fix §1/§2)
 * These three flags carry a **completely different** correction than the paragraph above. They exist
 * because the real Camera2-to-CameraX-buffer *position* map
 * (`dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3`, classified by
 * `classifySensorToBufferMatrix`) is not guaranteed to be the simple axis-aligned scale+translate this
 * codebase's CAM-2c analysis originally assumed — it may, in principle, permute which active-array
 * axis drives which buffer axis (`ORTHOGONAL_90`/`ORTHOGONAL_270`) and/or require a buffer-space
 * pinhole coefficient that came out algebraically negative before sign-normalization (see
 * `dev.pointtosky.core.astro.projection.camera.mapActiveArrayIntrinsicsThroughMatrix`, which derives
 * these three flags alongside [focalLengthXPx]/[focalLengthYPx]/[principalPointXPx]/[principalPointYPx]
 * themselves — never independently of them).
 *
 * **Why this can never double up with `rotationDegrees`.** The sensor-to-buffer matrix is a
 * **position** map (Camera2 active-array pixel → this exact buffer's pixel), resolved once per camera
 * session, before any star is projected. `rotationDegrees` (via
 * [dev.pointtosky.core.astro.projection.camera.CropScaleTransform]/
 * `dev.pointtosky.core.astro.projection.camera.prediction.DisplayAlignedOpticalToBufferOpticalTransform`)
 * instead rotates a **ray direction** — [dev.pointtosky.core.astro.projection.camera.prediction.BufferOpticalCameraVector]'s
 * `x`/`y`, already expressed "relative to the buffer's own, un-rotated row/column axes" per that class's
 * own KDoc — applied fresh for every star, at projection time. [project] receives that
 * already-buffer-relative `normalizedX`/`normalizedY` and only ever decides, once, at construction time
 * (via these three flags), *which* of the two incoming numbers is multiplied by [focalLengthXPx] vs
 * [focalLengthYPx], and with which sign. It never rotates anything a second time; it relabels which
 * already-buffer-relative axis is "X" and which is "Y", and never touches `rotationDegrees` at all.
 *
 * All three default to `false`, exactly reproducing the original, pre-CAM-2c-fix formula
 * (`x = focalLengthXPx·normalizedX + principalPointXPx`, `y = focalLengthYPx·normalizedY +
 * principalPointYPx`) — 100% backward compatible for every caller that never sets them.
 *
 * @property focalLengthXPx horizontal focal length in buffer pixels. Finite, strictly positive.
 * @property focalLengthYPx vertical focal length in buffer pixels. Finite, strictly positive.
 * @property principalPointXPx principal point X in buffer pixels. Finite.
 * @property principalPointYPx principal point Y in buffer pixels. Finite.
 * @property imageWidthPx full analyzed-buffer width in pixels. Finite, strictly positive.
 * @property imageHeightPx full analyzed-buffer height in pixels. Finite, strictly positive.
 * @property axisSwapped when `true`, [project] multiplies [focalLengthXPx] by the incoming
 *   `normalizedY` (not `normalizedX`) and [focalLengthYPx] by the incoming `normalizedX` — see the
 *   class KDoc section above.
 * @property negateXInput when `true`, [project] negates whichever normalized input feeds
 *   [focalLengthXPx] (see [axisSwapped]) before multiplying.
 * @property negateYInput the [focalLengthYPx] analogue of [negateXInput].
 */
data class PinholeProjectionModel(
    val focalLengthXPx: Double,
    val focalLengthYPx: Double,
    val principalPointXPx: Double,
    val principalPointYPx: Double,
    val imageWidthPx: Double,
    val imageHeightPx: Double,
    val axisSwapped: Boolean = false,
    val negateXInput: Boolean = false,
    val negateYInput: Boolean = false,
) {
    init {
        require(focalLengthXPx.isFinite() && focalLengthXPx > 0.0) {
            "focalLengthXPx must be finite and strictly positive; was $focalLengthXPx"
        }
        require(focalLengthYPx.isFinite() && focalLengthYPx > 0.0) {
            "focalLengthYPx must be finite and strictly positive; was $focalLengthYPx"
        }
        require(principalPointXPx.isFinite()) { "principalPointXPx must be finite; was $principalPointXPx" }
        require(principalPointYPx.isFinite()) { "principalPointYPx must be finite; was $principalPointYPx" }
        require(imageWidthPx.isFinite() && imageWidthPx > 0.0) {
            "imageWidthPx must be finite and strictly positive; was $imageWidthPx"
        }
        require(imageHeightPx.isFinite() && imageHeightPx > 0.0) {
            "imageHeightPx must be finite and strictly positive; was $imageHeightPx"
        }
    }

    /**
     * Projects normalized camera-plane coordinates ([CameraDirectionProjection.InFront.normalizedX]/
     * `normalizedY`) into buffer-space pixels. With every flag at its default `false`: `u =
     * fx·normalizedX + cx`, `v = fy·normalizedY + cy`. See the class KDoc for what [axisSwapped]/
     * [negateXInput]/[negateYInput] change and why that can never double up with the separate,
     * untouched `rotationDegrees`-driven ray rotation. Never clamped to [imageWidthPx]/[imageHeightPx]
     * — a point outside the image is a valid, meaningful result (see
     * [dev.pointtosky.core.astro.projection.camera.PixelRect.contains] for the separate classification
     * step).
     */
    fun project(
        normalizedX: Double,
        normalizedY: Double,
    ): PixelPoint {
        val xInput = if (axisSwapped) normalizedY else normalizedX
        val yInput = if (axisSwapped) normalizedX else normalizedY
        val signedXInput = if (negateXInput) -xInput else xInput
        val signedYInput = if (negateYInput) -yInput else yInput
        return PixelPoint(
            x = focalLengthXPx * signedXInput + principalPointXPx,
            y = focalLengthYPx * signedYInput + principalPointYPx,
        )
    }

    /**
     * The exact inverse of [project]: turns one analysis-buffer [PixelPoint] back into the **unit**
     * native-buffer optical camera ray that projects onto it.
     *
     * ## Why this lives here and not in the caller
     * [project] is not a bare `f·x + c`: [axisSwapped]/[negateXInput]/[negateYInput] decide which
     * incoming normalized component is multiplied by which focal length and with which sign, and those
     * flags are derived alongside [focalLengthXPx]/[principalPointXPx] by
     * `mapActiveArrayIntrinsicsThroughMatrix`, never independently. A consumer that wrote its own
     * inverse would be re-deriving that convention from the outside and would become a second,
     * unversioned camera-coordinate contract the moment either side changed. There is exactly one
     * inversion of this model, and it is here, beside the forward map it inverts.
     *
     * ## Space and convention
     * [point] is in the **full analyzed-buffer** space [project] produces — unrotated, uncropped, in the
     * project's continuous edge-coordinate convention (raster sample `[x, y]` centred at
     * `(x + 0.5, y + 0.5)`; see [dev.pointtosky.core.astro.projection.camera.PixelPoint]'s file KDoc).
     * That is the same space [dev.pointtosky.core.astro.projection.camera.detect.DetectedSource]'s
     * centroids, [PredictedStarProjection.imagePoint], and `SkyPredictedStar.imageXPx`/`imageYPx` live
     * in, so a detected centroid can be turned into a ray with no transform in between. No display or
     * viewport mapping happens here: [dev.pointtosky.core.astro.projection.camera.CropScaleTransform]
     * is a separate, later stage and applying it here would rotate the result twice.
     *
     * The result is a [BufferOpticalCameraVector] — the same native-buffer optical frame
     * [projectBufferOpticalDirection] consumes (`+x` right, `+y` down, `+z` forward) — deliberately, not
     * a bare 3-tuple: a display-aligned [OpticalCameraVector] is a different frame, and keeping them
     * distinct types is what stops the two being mixed. It is always **unit length** and always strictly
     * forward-facing (`z > 0`), because every finite image point corresponds to a direction in front of
     * the camera; the behind-camera case exists only in the forward direction, where
     * [projectBufferOpticalDirection] rejects it before any pixel is computed.
     *
     * ## Exactness
     * For any [BufferOpticalCameraVector] with `z > 0`, `unprojectToCameraRay(project(v.x/v.z, v.y/v.z))`
     * returns `v` normalized — that is, the round trip is the identity on directions, up to floating
     * point. `PinholeProjectionModelUnprojectTest` pins this for a centred and an off-centre principal
     * point, for `fx != fy`, and for every combination of the three orientation flags.
     *
     * Ray magnitudes are never clamped and [point] is never required to lie inside the image: a
     * detection near the frame border and a candidate that projected just outside it are both
     * meaningful, exactly as [project] never clamps its own output.
     *
     * ## Scale safety
     * "Any finite pixel" means the whole finite range, not just plausible ones, and the normalization
     * below is written for that: it divides by the largest component before squaring, so no intermediate
     * ever overflows and no component is flushed to zero on the way. Squaring the raw components first —
     * the obvious `sqrt(x² + y² + 1)` — breaks down well inside the finite range: a `PixelPoint` near
     * `Double.MAX_VALUE` makes `x²` infinite and the whole vector collapses to `(0, 0, 0)`, which is
     * finite, silent, and not a direction. That matters beyond tidiness, because
     * [dev.pointtosky.core.astro.projection.camera.match.angleBetweenRad] enforces the unit-ray promise
     * made here; a producer that quietly broke it would surface as a rejected argument in the consumer.
     * `PinholeProjectionModelUnprojectTest` pins the extremes, including that the returned direction
     * still has the right `x/z` and `y/z` ratios rather than merely being some unit vector.
     *
     * The one way out of that guarantee is a degenerate model rather than an extreme pixel: a focal
     * length far below `1.0`, or a principal point whose magnitude rivals `Double.MAX_VALUE`, can
     * overflow the subtract-and-divide *above* this normalization. The non-finite component is then
     * rejected by [BufferOpticalCameraVector]'s own constructor instead of being normalized into a
     * plausible-looking ray.
     */
    fun unprojectToCameraRay(point: PixelPoint): BufferOpticalCameraVector {
        // Undo project()'s pixel scaling first, then its axis relabelling, in the reverse order it was
        // applied: pixels -> signed focal-scaled inputs -> unsigned inputs -> normalized components.
        val signedXInput = (point.x - principalPointXPx) / focalLengthXPx
        val signedYInput = (point.y - principalPointYPx) / focalLengthYPx
        val xInput = if (negateXInput) -signedXInput else signedXInput
        val yInput = if (negateYInput) -signedYInput else signedYInput
        val normalizedX = if (axisSwapped) yInput else xInput
        val normalizedY = if (axisSwapped) xInput else yInput

        // (normalizedX, normalizedY, 1) is the ray at unit depth, by projectBufferOpticalDirection's own
        // definition (normalizedX = cameraX / cameraZ). Normalizing it directly would square the raw
        // components, and squaring is what fails first: a pixel far enough off axis makes
        // normalizedX * normalizedX overflow to Infinity, after which every component divides to 0.0 and
        // a perfectly finite pixel yields the zero vector — finite, but neither unit nor forward-facing.
        // Dividing through by the largest component first keeps all three squares in [0, 1] and costs
        // nothing on the ordinary path: whenever |normalizedX| and |normalizedY| are both <= 1 the scale
        // is exactly 1.0 and the arithmetic below is bit-for-bit the unscaled form.
        val scale = max(1.0, max(abs(normalizedX), abs(normalizedY)))
        val scaledX = normalizedX / scale
        val scaledY = normalizedY / scale
        val scaledZ = 1.0 / scale

        // In [1, sqrt(3)] always: by construction one of the three scaled components is exactly ±1. So
        // this can neither divide by zero nor amplify a component, and scaledZ — itself never smaller
        // than 1 / Double.MAX_VALUE — stays strictly positive through the division.
        val scaledLength = sqrt(scaledX * scaledX + scaledY * scaledY + scaledZ * scaledZ)
        return BufferOpticalCameraVector(
            x = scaledX / scaledLength,
            y = scaledY / scaledLength,
            z = scaledZ / scaledLength,
        )
    }

    companion object {
        /**
         * Builds the model for one [CameraSessionGeometry], deriving pixel focal lengths from
         * [CameraIntrinsicsResolution.intrinsics]' field-of-view degrees over the full analyzed-buffer
         * size ([CameraSessionGeometry.cropScaleTransform]'s `sourceBufferSize` — equal to
         * `frame.bufferWidthPx`/`bufferHeightPx` by [CameraSessionGeometry]'s own invariant):
         * ```text
         * fx = imageWidthPx  / (2 · tan(horizontalFovDeg / 2))
         * fy = imageHeightPx / (2 · tan(verticalFovDeg   / 2))
         * ```
         * This is only valid when [geometry].intrinsics.intrinsics.[dev.pointtosky.core.astro.projection.camera.CameraIntrinsics.reference]
         * is a [CameraIntrinsicsReference.AnalysisBuffer] whose
         * [CameraIntrinsicsReference.AnalysisBuffer.widthPx]/[CameraIntrinsicsReference.AnalysisBuffer.heightPx]
         * **exactly** match [geometry].frame's buffer dimensions — i.e. the FOV is already known,
         * with recorded dimensions to prove it, to be measured over this exact pixel grid. Matching
         * aspect ratio alone is not enough (see [CameraIntrinsicsReference.AnalysisBuffer]'s KDoc): a
         * `1000x500`-referenced value must not be silently reused for a `2000x1000` buffer just
         * because the shape matches. [CameraIntrinsicsReference.PhysicalSensor] (`CAMERA_CHARACTERISTICS`
         * FOV, measured over the full physical sensor with no recorded crop/scale relationship to any
         * particular `ImageAnalysis` output resolution) and [CameraIntrinsicsReference.Unspecified]
         * (a dimensionless legacy fallback, e.g. resolved before the first analyzed frame's real
         * dimensions were known) are both never valid here. Callers must check
         * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsics.reference] themselves — via
         * `projectStars`'s `StarPredictionBatchResult.IntrinsicsMappingUnavailable` path — **before**
         * calling this function; this is a defense-in-depth contract check for a caller that skipped
         * that step, not the expected-runtime-outcome path.
         *
         * The principal point defaults to the buffer's geometric center
         * (`imageWidthPx/2`, `imageHeightPx/2`) when
         * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsics.principalPointXPx]/
         * `principalPointYPx` is absent — which is always, as of CAM-1b (see
         * `docs/camera_coordinate_calibration_contract.md` §3.4).
         *
         * @throws IllegalArgumentException if the intrinsics' [dev.pointtosky.core.astro.projection.camera.CameraIntrinsics.reference]
         *   is not an [CameraIntrinsicsReference.AnalysisBuffer], or its dimensions do not exactly
         *   match [geometry].frame's buffer dimensions.
         */
        fun forGeometry(geometry: CameraSessionGeometry): PinholeProjectionModel {
            val intrinsics = geometry.intrinsics.intrinsics
            val reference = intrinsics.reference
            require(reference is CameraIntrinsicsReference.AnalysisBuffer) {
                "PinholeProjectionModel requires an AnalysisBuffer-referenced intrinsics value; was $reference. " +
                    "Callers must check this via projectStars' StarPredictionBatchResult.IntrinsicsMappingUnavailable path first."
            }
            require(reference.widthPx == geometry.frame.bufferWidthPx && reference.heightPx == geometry.frame.bufferHeightPx) {
                "AnalysisBuffer reference dimensions (${reference.widthPx}x${reference.heightPx}) must exactly match " +
                    "geometry.frame buffer dimensions (${geometry.frame.bufferWidthPx}x${geometry.frame.bufferHeightPx})"
            }
            val widthPx = geometry.cropScaleTransform.sourceBufferSize.width
            val heightPx = geometry.cropScaleTransform.sourceBufferSize.height

            val fx = widthPx / (2.0 * tan(Math.toRadians(intrinsics.horizontalFovDeg) / 2.0))
            val fy = heightPx / (2.0 * tan(Math.toRadians(intrinsics.verticalFovDeg) / 2.0))
            val cx = intrinsics.principalPointXPx ?: (widthPx / 2.0)
            val cy = intrinsics.principalPointYPx ?: (heightPx / 2.0)

            return PinholeProjectionModel(
                focalLengthXPx = fx,
                focalLengthYPx = fy,
                principalPointXPx = cx,
                principalPointYPx = cy,
                imageWidthPx = widthPx,
                imageHeightPx = heightPx,
                axisSwapped = intrinsics.axisSwapped,
                negateXInput = intrinsics.negateXInput,
                negateYInput = intrinsics.negateYInput,
            )
        }
    }
}
