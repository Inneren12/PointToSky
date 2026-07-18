package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import kotlin.math.abs
import kotlin.math.max

/**
 * `internalDebug`-only. CAM-2c dual-basis geometry classifier — the recon follow-up to
 * [assessWholeActiveArrayMappingHypothesis] (`docs/recon/cam_2c_sensor_to_buffer_domain_recon.md` §5):
 * the existing binary verdict expects an *exact bounds fit* and therefore labels CameraX 1.4.2's own
 * intended inverse-`ScaleToFit.CENTER` geometry (uniform scale + symmetric center-crop overflow
 * whenever source and buffer aspect ratios differ) as `WHOLE_ACTIVE_ARRAY_HYPOTHESIS_MISMATCH`. That
 * binary verdict and its exported fields are **preserved unchanged** for compatibility; this file adds
 * an independent, finer-grained classification of *what shape* the mapped-bounds relationship actually
 * has.
 *
 * ## Hypothesis-scoped, evidence-only
 * Like the binary verdict, every result here is conditioned on an assumed source rectangle a caller
 * names explicitly (in the dual-basis diagnostic, always via a fully-qualified
 * [CameraCoordinateBasis]). A classification — including [WholeActiveArrayGeometryClass.UNIFORM_SCALE_CENTER_CROP],
 * the class the traced CameraX 1.4.2 construction produces — is **evidence about the matrix's own
 * numbers under that assumption only**. It is never proof of the matrix's real source domain, never a
 * measurement of frame content, and it must never feed [SensorToBufferDomainProof] (see
 * [evidenceOnlySensorToBufferDomainProof], which remains unable to emit any `Proven*` variant).
 */
internal enum class WholeActiveArrayGeometryClass {
    /** The mapped source rect lands on the buffer rect exactly (within float-noise tolerance) on all
     * four edges — only possible for matching aspect ratios (given uniform scale) since anisotropic
     * exact fits classify as [NON_UNIFORM_SCALE] first. */
    EXACT_BOUNDS_FIT,

    /** Uniform (isotropic) scale; one axis fits within float noise; the other overflows the buffer on
     * both sides by a meaningful, symmetric amount — the exact shape CameraX 1.4.2's traced
     * inverse-`ScaleToFit.CENTER` construction produces for aspect-mismatched pairs (recon §2.1/§4). */
    UNIFORM_SCALE_CENTER_CROP,

    /** Uniform scale with meaningful overflow that is *not* symmetric within tolerance — not the
     * traced CameraX construction's shape. */
    UNIFORM_SCALE_ASYMMETRIC_CROP,

    /** Uniform scale with the mapped bounds meaningfully *inside* the buffer on an axis (padding /
     * letterbox) — the geometric opposite of crop; also not the traced construction's shape. */
    UNIFORM_SCALE_LETTERBOX,

    /** Axis-aligned but anisotropic scale (`scaleX` ≠ `scaleY` beyond
     * [WholeActiveArrayGeometryTolerances.scaleIsotropyRelativeTolerance]) — e.g. a hypothetical
     * stretch-to-fill mapping. */
    NON_UNIFORM_SCALE,

    /** Off-diagonal (`m01`/`m10`) or projective (`m20`/`m21`/`m22`) structure beyond
     * [WholeActiveArrayGeometryTolerances.affineStructureTolerance]. Deliberately one bucket: this
     * classifier's scope is CameraX 1.4.2's axis-aligned construction, so *any* rotated, sheared,
     * mirrored-with-skew, or perspective matrix is equally outside it (the finer structural story is
     * already told by [dev.pointtosky.core.astro.projection.camera.classifySensorToBufferMatrix],
     * which is reported alongside, never replaced). */
    SHEAR_OR_PERSPECTIVE,

    /** A scale coefficient is ≈ 0 (the mapped source collapses to a line/point) — not invertible,
     * no crop/letterbox statement is meaningful. */
    DEGENERATE,

    /** Source or buffer dimensions missing/non-positive — no assessment was possible. */
    INSUFFICIENT_INPUT,

    /** Finite, axis-aligned, positive-scale, but matching none of the shapes above within the
     * documented tolerances (including the honest grey zone between float noise and meaningful
     * geometry, and negative/mirrored axis-aligned scales) — reported as-is, never rounded to the
     * nearest named class. */
    UNEXPLAINED,
}

/**
 * `internalDebug`-only. The classifier's four **separate** tolerances (recon §5: float-storage noise
 * and geometric crop magnitude must never share one number).
 *
 * @property floatNoiseTolerancePx bound for residuals attributable purely to `android.graphics.Matrix`
 *   float32 storage at CameraX-realistic coordinate magnitudes (buffers/arrays ≤ ~8192 px: one float32
 *   ULP at 640 is ≈ 6.1e-5 px; the real Pixel 9 matrix's total mapped-bounds error is ≈ 2.3e-5 px —
 *   recon §4). `1e-3` px gives ≥ 40× headroom over the observed noise while sitting ≥ 900× below the
 *   smallest real geometric crop this codebase has measured (0.941 px/side).
 * @property geometricMagnitudeTolerancePx minimum overflow/underflow magnitude treated as *meaningful*
 *   geometry (a real crop or letterbox) rather than noise: `1e-2` px — 10× the float-noise bound, 94×
 *   below the smallest observed real crop. Residuals between the two bounds are an honest grey zone
 *   and classify [WholeActiveArrayGeometryClass.UNEXPLAINED], never silently absorbed into "fit" or
 *   promoted to "crop".
 * @property scaleIsotropyRelativeTolerance relative bound for `|scaleX − scaleY| / max(|scaleX|, |scaleY|)`:
 *   float32 storage introduces relative error ≈ 6e-8; the smallest real anisotropy an integer-dimension
 *   CameraX pair produces is ≥ ~4e-3 (e.g. 4080×3072 vs 640×480). `1e-4` splits the two by > 3 orders
 *   of magnitude each way.
 * @property affineStructureTolerance absolute bound for off-diagonal/perspective coefficients being
 *   "structurally zero" — matches
 *   [dev.pointtosky.core.astro.projection.camera.DEFAULT_SENSOR_TO_BUFFER_CLASSIFICATION_TOLERANCE]
 *   (`1e-6`), float noise only, never a physically meaningful margin.
 */
internal data class WholeActiveArrayGeometryTolerances(
    val floatNoiseTolerancePx: Double = DEFAULT_FLOAT_NOISE_TOLERANCE_PX,
    val geometricMagnitudeTolerancePx: Double = DEFAULT_GEOMETRIC_MAGNITUDE_TOLERANCE_PX,
    val scaleIsotropyRelativeTolerance: Double = DEFAULT_SCALE_ISOTROPY_RELATIVE_TOLERANCE,
    val affineStructureTolerance: Double = DEFAULT_AFFINE_STRUCTURE_TOLERANCE,
) {
    init {
        require(floatNoiseTolerancePx.isFinite() && floatNoiseTolerancePx > 0.0)
        require(geometricMagnitudeTolerancePx.isFinite() && geometricMagnitudeTolerancePx > floatNoiseTolerancePx) {
            "geometricMagnitudeTolerancePx must exceed floatNoiseTolerancePx"
        }
        require(scaleIsotropyRelativeTolerance.isFinite() && scaleIsotropyRelativeTolerance > 0.0)
        require(affineStructureTolerance.isFinite() && affineStructureTolerance > 0.0)
    }
}

internal const val DEFAULT_FLOAT_NOISE_TOLERANCE_PX: Double = 1e-3
internal const val DEFAULT_GEOMETRIC_MAGNITUDE_TOLERANCE_PX: Double = 1e-2
internal const val DEFAULT_SCALE_ISOTROPY_RELATIVE_TOLERANCE: Double = 1e-4
internal const val DEFAULT_AFFINE_STRUCTURE_TOLERANCE: Double = 1e-6

/**
 * `internalDebug`-only. Full classifier result. All numeric fields are `null` exactly when the
 * corresponding computation was impossible for the returned [geometryClass]
 * ([WholeActiveArrayGeometryClass.INSUFFICIENT_INPUT] carries no numbers;
 * [WholeActiveArrayGeometryClass.SHEAR_OR_PERSPECTIVE]/[WholeActiveArrayGeometryClass.DEGENERATE]
 * carry the raw coefficients but no overflow/symmetry analysis).
 *
 * Overflow sign convention: positive = mapped bounds extend *beyond* the buffer edge (crop);
 * negative = mapped bounds stop *short* of it (letterbox). `overflowLeftPx`/`overflowTopPx` are
 * `-(mappedLeft - 0)` / `-(mappedTop - 0)` so that "sticks out past the left/top edge" is positive,
 * mirroring `overflowRightPx = mappedRight − bufferWidth`.
 */
internal data class WholeActiveArrayGeometryAssessment(
    val geometryClass: WholeActiveArrayGeometryClass,
    val mappedBoundsPx: SensorToBufferDomainBounds?,
    val mappedCenterXPx: Double?,
    val mappedCenterYPx: Double?,
    val scaleX: Double?,
    val scaleY: Double?,
    val translationX: Double?,
    val translationY: Double?,
    val isotropyResidual: Double?,
    val centerResidualXPx: Double?,
    val centerResidualYPx: Double?,
    val overflowLeftPx: Double?,
    val overflowTopPx: Double?,
    val overflowRightPx: Double?,
    val overflowBottomPx: Double?,
    val horizontalSymmetryResidualPx: Double?,
    val verticalSymmetryResidualPx: Double?,
    val reason: String,
)

/**
 * Classifies the mapped-bounds geometry of [matrix] applied to the assumed source rectangle
 * `[sourceLeftPx, sourceTopPx] — [sourceLeftPx+sourceWidthPx, sourceTopPx+sourceHeightPx]` (full
 * native coordinates — a caller assessing an offset active-array rect passes the real offsets)
 * against the buffer rect `[0, 0] — [bufferWidthPx, bufferHeightPx]`.
 *
 * Decision order (first match wins):
 * 1. missing/non-positive dimensions → [WholeActiveArrayGeometryClass.INSUFFICIENT_INPUT];
 * 2. projective or off-diagonal structure beyond [WholeActiveArrayGeometryTolerances.affineStructureTolerance]
 *    → [WholeActiveArrayGeometryClass.SHEAR_OR_PERSPECTIVE];
 * 3. a scale coefficient ≈ 0 → [WholeActiveArrayGeometryClass.DEGENERATE];
 * 4. a negative scale coefficient (mirrored / 180°-style) → [WholeActiveArrayGeometryClass.UNEXPLAINED];
 * 5. anisotropy beyond [WholeActiveArrayGeometryTolerances.scaleIsotropyRelativeTolerance]
 *    → [WholeActiveArrayGeometryClass.NON_UNIFORM_SCALE];
 * 6. all four edge residuals within float noise → [WholeActiveArrayGeometryClass.EXACT_BOUNDS_FIT];
 * 7. one axis fits within float noise, the other overflows meaningfully on both sides —
 *    symmetric within float noise → [WholeActiveArrayGeometryClass.UNIFORM_SCALE_CENTER_CROP],
 *    otherwise → [WholeActiveArrayGeometryClass.UNIFORM_SCALE_ASYMMETRIC_CROP];
 * 8. one axis fits within float noise, the other meaningfully *inside* the buffer on both sides
 *    → [WholeActiveArrayGeometryClass.UNIFORM_SCALE_LETTERBOX];
 * 9. anything else (grey-zone residuals, mixed overflow/underflow, both axes off) →
 *    [WholeActiveArrayGeometryClass.UNEXPLAINED].
 */
internal fun assessWholeActiveArrayGeometry(
    matrix: SensorToBufferMatrix3,
    sourceLeftPx: Int?,
    sourceTopPx: Int?,
    sourceWidthPx: Int?,
    sourceHeightPx: Int?,
    bufferWidthPx: Int?,
    bufferHeightPx: Int?,
    tolerances: WholeActiveArrayGeometryTolerances = WholeActiveArrayGeometryTolerances(),
): WholeActiveArrayGeometryAssessment {
    fun insufficient(reason: String) =
        WholeActiveArrayGeometryAssessment(
            geometryClass = WholeActiveArrayGeometryClass.INSUFFICIENT_INPUT,
            mappedBoundsPx = null, mappedCenterXPx = null, mappedCenterYPx = null,
            scaleX = null, scaleY = null, translationX = null, translationY = null,
            isotropyResidual = null, centerResidualXPx = null, centerResidualYPx = null,
            overflowLeftPx = null, overflowTopPx = null, overflowRightPx = null, overflowBottomPx = null,
            horizontalSymmetryResidualPx = null, verticalSymmetryResidualPx = null,
            reason = reason,
        )

    if (bufferWidthPx == null || bufferHeightPx == null || bufferWidthPx <= 0 || bufferHeightPx <= 0) {
        return insufficient(
            "buffer width/height missing or not strictly positive " +
                "(bufferWidthPx=$bufferWidthPx, bufferHeightPx=$bufferHeightPx)",
        )
    }
    if (sourceLeftPx == null || sourceTopPx == null ||
        sourceWidthPx == null || sourceHeightPx == null || sourceWidthPx <= 0 || sourceHeightPx <= 0
    ) {
        return insufficient(
            "assumed source rect missing or not strictly positive (left=$sourceLeftPx, top=$sourceTopPx, " +
                "width=$sourceWidthPx, height=$sourceHeightPx)",
        )
    }

    fun structural(geometryClass: WholeActiveArrayGeometryClass, reason: String) =
        WholeActiveArrayGeometryAssessment(
            geometryClass = geometryClass,
            mappedBoundsPx = null, mappedCenterXPx = null, mappedCenterYPx = null,
            scaleX = matrix.m00, scaleY = matrix.m11, translationX = matrix.m02, translationY = matrix.m12,
            isotropyResidual = null, centerResidualXPx = null, centerResidualYPx = null,
            overflowLeftPx = null, overflowTopPx = null, overflowRightPx = null, overflowBottomPx = null,
            horizontalSymmetryResidualPx = null, verticalSymmetryResidualPx = null,
            reason = reason,
        )

    val affTol = tolerances.affineStructureTolerance
    val isAffine = abs(matrix.m20) <= affTol && abs(matrix.m21) <= affTol && abs(matrix.m22 - 1.0) <= affTol
    val hasOffDiagonal = abs(matrix.m01) > affTol || abs(matrix.m10) > affTol
    if (!isAffine || hasOffDiagonal) {
        return structural(
            WholeActiveArrayGeometryClass.SHEAR_OR_PERSPECTIVE,
            "off-diagonal or projective structure beyond affineStructureTolerance=$affTol " +
                "(m01=${matrix.m01}, m10=${matrix.m10}, m20=${matrix.m20}, m21=${matrix.m21}, m22=${matrix.m22}) — " +
                "outside this classifier's axis-aligned scope; see transformClass for the structural story",
        )
    }
    if (abs(matrix.m00) <= affTol || abs(matrix.m11) <= affTol) {
        return structural(
            WholeActiveArrayGeometryClass.DEGENERATE,
            "a scale coefficient is ~0 (m00=${matrix.m00}, m11=${matrix.m11}) — the mapped source collapses",
        )
    }
    if (matrix.m00 < 0.0 || matrix.m11 < 0.0) {
        return structural(
            WholeActiveArrayGeometryClass.UNEXPLAINED,
            "negative axis-aligned scale (m00=${matrix.m00}, m11=${matrix.m11}) — a mirrored/180-style map, " +
                "not the traced CameraX 1.4.2 construction",
        )
    }

    // Axis-aligned positive scale+translate from here on. Map the assumed source rect (native
    // coordinates, offsets included) in double precision.
    val left = sourceLeftPx.toDouble()
    val top = sourceTopPx.toDouble()
    val right = left + sourceWidthPx.toDouble()
    val bottom = top + sourceHeightPx.toDouble()
    val mappedLeft = matrix.m00 * left + matrix.m02
    val mappedRight = matrix.m00 * right + matrix.m02
    val mappedTop = matrix.m11 * top + matrix.m12
    val mappedBottom = matrix.m11 * bottom + matrix.m12
    val mapped = SensorToBufferDomainBounds(leftPx = mappedLeft, topPx = mappedTop, rightPx = mappedRight, bottomPx = mappedBottom)
    val mappedCenterX = matrix.m00 * (left + right) / 2.0 + matrix.m02
    val mappedCenterY = matrix.m11 * (top + bottom) / 2.0 + matrix.m12
    val bufW = bufferWidthPx.toDouble()
    val bufH = bufferHeightPx.toDouble()

    // Positive overflow = mapped bounds extend beyond the buffer edge; negative = stop short of it.
    val overflowLeft = -mappedLeft
    val overflowTop = -mappedTop
    val overflowRight = mappedRight - bufW
    val overflowBottom = mappedBottom - bufH
    val horizontalSymmetryResidual = abs(overflowLeft - overflowRight)
    val verticalSymmetryResidual = abs(overflowTop - overflowBottom)
    val isotropyResidual = abs(matrix.m00 - matrix.m11) / max(abs(matrix.m00), abs(matrix.m11))
    val centerResidualX = mappedCenterX - bufW / 2.0
    val centerResidualY = mappedCenterY - bufH / 2.0

    fun result(geometryClass: WholeActiveArrayGeometryClass, reason: String) =
        WholeActiveArrayGeometryAssessment(
            geometryClass = geometryClass,
            mappedBoundsPx = mapped,
            mappedCenterXPx = mappedCenterX, mappedCenterYPx = mappedCenterY,
            scaleX = matrix.m00, scaleY = matrix.m11, translationX = matrix.m02, translationY = matrix.m12,
            isotropyResidual = isotropyResidual,
            centerResidualXPx = centerResidualX, centerResidualYPx = centerResidualY,
            overflowLeftPx = overflowLeft, overflowTopPx = overflowTop,
            overflowRightPx = overflowRight, overflowBottomPx = overflowBottom,
            horizontalSymmetryResidualPx = horizontalSymmetryResidual,
            verticalSymmetryResidualPx = verticalSymmetryResidual,
            reason = reason,
        )

    if (isotropyResidual > tolerances.scaleIsotropyRelativeTolerance) {
        return result(
            WholeActiveArrayGeometryClass.NON_UNIFORM_SCALE,
            "anisotropic scale: |m00−m11|/max = $isotropyResidual exceeds " +
                "scaleIsotropyRelativeTolerance=${tolerances.scaleIsotropyRelativeTolerance}",
        )
    }

    val noise = tolerances.floatNoiseTolerancePx
    val geom = tolerances.geometricMagnitudeTolerancePx
    val edges = listOf(overflowLeft, overflowTop, overflowRight, overflowBottom)
    if (edges.all { abs(it) <= noise }) {
        return result(
            WholeActiveArrayGeometryClass.EXACT_BOUNDS_FIT,
            "all four edge residuals within floatNoiseTolerancePx=$noise",
        )
    }

    // Per-axis shape: "fits" (both residuals within noise); "crops" (meaningful overflow on at least
    // one side, nothing meaningfully inside — a one-sided crop with the other edge fitting exactly is
    // still a crop, its asymmetry is judged separately by the symmetry residual); "pads" (the mirror
    // case, meaningfully inside on at least one side, nothing overflowing); else indeterminate.
    fun axisShape(sideA: Double, sideB: Double): String =
        when {
            abs(sideA) <= noise && abs(sideB) <= noise -> "fits"
            (sideA > geom || sideB > geom) && sideA >= -noise && sideB >= -noise -> "crops"
            (sideA < -geom || sideB < -geom) && sideA <= noise && sideB <= noise -> "pads"
            else -> "indeterminate"
        }

    val xShape = axisShape(overflowLeft, overflowRight)
    val yShape = axisShape(overflowTop, overflowBottom)

    return when {
        xShape == "fits" && yShape == "crops" || yShape == "fits" && xShape == "crops" -> {
            val symmetryResidual = if (yShape == "crops") verticalSymmetryResidual else horizontalSymmetryResidual
            if (symmetryResidual <= noise) {
                result(
                    WholeActiveArrayGeometryClass.UNIFORM_SCALE_CENTER_CROP,
                    "uniform scale; ${if (yShape == "crops") "vertical" else "horizontal"} overflow symmetric " +
                        "within floatNoiseTolerancePx=$noise — the shape the traced CameraX 1.4.2 " +
                        "construction produces; evidence under the assumed source rect only, never proof " +
                        "of the matrix's real source domain or of frame content",
                )
            } else {
                result(
                    WholeActiveArrayGeometryClass.UNIFORM_SCALE_ASYMMETRIC_CROP,
                    "uniform scale with asymmetric overflow (symmetry residual $symmetryResidual px > $noise px)",
                )
            }
        }
        xShape == "fits" && yShape == "pads" || yShape == "fits" && xShape == "pads" ->
            result(
                WholeActiveArrayGeometryClass.UNIFORM_SCALE_LETTERBOX,
                "uniform scale; mapped bounds stop meaningfully inside the buffer on the " +
                    "${if (yShape == "pads") "vertical" else "horizontal"} axis (letterbox/padding)",
            )
        else ->
            result(
                WholeActiveArrayGeometryClass.UNEXPLAINED,
                "axis shapes x=$xShape y=$yShape match no named class within the documented tolerances " +
                    "(grey-zone or mixed residuals are reported as-is, never rounded to the nearest class)",
            )
    }
}
