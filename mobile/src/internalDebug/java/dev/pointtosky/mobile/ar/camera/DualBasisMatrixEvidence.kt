package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import dev.pointtosky.core.astro.projection.camera.classifySensorToBufferMatrix
import kotlin.math.abs
import kotlin.math.max

/**
 * `internalDebug`-only. CAM-2c dual-basis diagnostic core (recon
 * `docs/recon/cam_2c_sensor_to_buffer_domain_recon.md` §1/§2.3): evaluates one observed
 * sensor-to-buffer matrix independently under **two explicitly labelled candidate source bases** —
 * the opened logical camera's active array ([MatrixBasisLabel.LOGICAL_OPENED_CAMERA_BASIS]) and the
 * selected physical camera's active array ([MatrixBasisLabel.SELECTED_PHYSICAL_CAMERA_BASIS]) — and
 * reports which basis, if any, the CameraX 1.4.2 implementation model
 * ([predictCameraX142SensorToBufferMatrix]) numerically explains.
 *
 * ## What a "match" here means — and what it never means
 * `matchesCameraX142Model = true` means: the observed matrix's coefficients reproduce, within
 * [CAMERAX_142_MODEL_MATCH_TOLERANCE_PX] over the mapped corners/center, the matrix CameraX 1.4.2
 * *would construct* from that basis's rect and this buffer. That is a statement about matrix
 * **metadata matching its own traced construction** — it is **not** a measurement of what the HAL
 * placed in the image. Frame-content correspondence remains unmeasured
 * ([DualBasisEvidenceLevel.FRAME_CONTENT_CORRESPONDENCE_UNMEASURED] is always attached), and no
 * result here may feed [SensorToBufferDomainProof] or unlock calibrated `AnalysisBuffer` intrinsics.
 */

/** Which candidate source basis a [BasisMatrixAssessment] was computed against. Every exported
 * assessment carries this label — never an unlabelled generic "active array assessment". */
internal enum class MatrixBasisLabel {
    LOGICAL_OPENED_CAMERA_BASIS,
    SELECTED_PHYSICAL_CAMERA_BASIS,
}

/** Evidence levels this diagnostic can honestly attach to a dual-basis report (recon §13's ladder).
 * There is deliberately no stronger label — in particular, nothing here can ever express
 * "frame content proven to use a basis". */
internal enum class DualBasisEvidenceLevel {
    /** The public `ImageInfo` Javadoc declares the transform's domain endpoints (opened camera's
     * `SENSOR_INFO_ACTIVE_ARRAY_SIZE` → full buffer) — an API-documented statement, present for every
     * assessment regardless of numeric outcome. */
    API_DECLARED_DOMAIN,

    /** A real device matrix was observed and captured for this assessment. */
    DEVICE_MATRIX_OBSERVED,

    /** The observed matrix numerically reproduces the CameraX 1.4.2 implementation model under at
     * least one labelled basis. Metadata-construction evidence only. */
    CAMERAX_IMPLEMENTATION_MODEL_MATCH,

    /** Always present: no frame-content measurement exists in this codebase; nothing here measures
     * what the HAL actually placed in the buffer. */
    FRAME_CONTENT_CORRESPONDENCE_UNMEASURED,
}

/**
 * `internalDebug`-only. Bound for the maximum mapped-point residual (over the source rect's four
 * corners and center) between the observed matrix and the [predictCameraX142SensorToBufferMatrix]
 * prediction, below which the observed matrix is considered to match the model under that basis.
 * `1e-2` px: the model's own float32 sequencing ambiguity (recon: Skia may compute the inverse
 * translation as `−t·(1/s)` rather than `−t/s`) contributes ≤ ~1e-4 px at active-array magnitudes,
 * giving 100× headroom, while any genuinely different basis rect (even a single-pixel dimension
 * difference) shifts mapped points by ≥ ~0.1 px — 10× above this bound.
 */
internal const val CAMERAX_142_MODEL_MATCH_TOLERANCE_PX: Double = 1e-2

/**
 * `internalDebug`-only. One basis's full assessment: the fully-qualified basis identity, the geometry
 * classification of the observed matrix under that basis, the CameraX 1.4.2 model prediction for that
 * basis, and observed-vs-predicted residuals.
 *
 * @property coefficientResiduals `observed − predicted`, in [SensorToBufferMatrix3] order
 *   (`m00, m01, m02, m10, m11, m12, m20, m21, m22`); `null` when no prediction was computable.
 * @property maxAbsCoefficientResidual max of `|coefficientResiduals|`.
 * @property maxMappedPointResidualPx max Euclidean distance between observed-mapped and
 *   predicted-mapped points over the basis rect's four corners and center.
 * @property matchesCameraX142Model `true` iff [maxMappedPointResidualPx] ≤
 *   [CAMERAX_142_MODEL_MATCH_TOLERANCE_PX]. Metadata-construction evidence only — see file KDoc.
 */
internal data class BasisMatrixAssessment(
    val basisLabel: MatrixBasisLabel,
    val basis: CameraCoordinateBasis,
    val geometry: WholeActiveArrayGeometryAssessment,
    val predicted: CameraX142PredictedSensorToBuffer?,
    val coefficientResiduals: List<Double>?,
    val maxAbsCoefficientResidual: Double?,
    val maxMappedPointResidualPx: Double?,
    val matchesCameraX142Model: Boolean?,
)

/** Typed comparison outcome across the two bases. */
internal enum class DualBasisComparisonVerdict {
    /** Only the opened logical camera's active-array basis reproduces the CameraX 1.4.2 model. */
    MATCHES_LOGICAL_BASIS_ONLY,

    /** Only the selected physical camera's active-array basis reproduces it. */
    MATCHES_PHYSICAL_BASIS_ONLY,

    /** Both bases reproduce the model — which, whenever the two rects are numerically equal, means
     * the bases are **numerically indistinguishable for this buffer**: the matrix cannot tell them
     * apart, and this verdict must never be read as "proven equal" or as identifying which sensor
     * produced frame content. */
    MATCHES_BOTH_BASES_NUMERICALLY_INDISTINGUISHABLE,

    /** Neither basis reproduces the model — the observed matrix is not explained by the traced
     * CameraX 1.4.2 construction under either candidate rect (e.g. the historical 1.3.4 identity
     * matrix, or an untraced OEM behavior). */
    MATCHES_NEITHER_BASIS,

    /** The comparison could not run: no matrix observed, or neither basis could be captured. */
    INSUFFICIENT_INPUT,
}

/**
 * `internalDebug`-only. The full dual-basis evidence result for one observed matrix + buffer.
 *
 * @property logical the logical-basis assessment, `null` when the opened logical camera's basis was
 *   unavailable (typed reason in [reason]) — never silently substituted by the physical basis.
 * @property physical the physical-basis assessment, `null` symmetrically.
 * @property basesNumericallyIndistinguishable `true` when both bases were captured and their rects
 *   are numerically equal — the two models are then the same model, and no matrix can distinguish
 *   them (recon fixture 15's honest outcome; never "proven equal").
 * @property betterPredictingBasis whichever basis has the strictly smaller
 *   [BasisMatrixAssessment.maxMappedPointResidualPx] (recon fixture 16) — `null` on a tie, when only
 *   one assessment exists, or when residuals are unavailable. A ranking, not a proof.
 * @property evidenceLevels always contains [DualBasisEvidenceLevel.API_DECLARED_DOMAIN] and
 *   [DualBasisEvidenceLevel.FRAME_CONTENT_CORRESPONDENCE_UNMEASURED]; plus
 *   [DualBasisEvidenceLevel.DEVICE_MATRIX_OBSERVED] when a matrix was assessed; plus
 *   [DualBasisEvidenceLevel.CAMERAX_IMPLEMENTATION_MODEL_MATCH] when at least one basis matched.
 */
internal data class DualBasisMatrixEvidence(
    val observedMatrix: SensorToBufferMatrix3?,
    val observedTransformClass: SensorToBufferTransformClass?,
    val bufferWidthPx: Int?,
    val bufferHeightPx: Int?,
    val logical: BasisMatrixAssessment?,
    val physical: BasisMatrixAssessment?,
    val comparisonVerdict: DualBasisComparisonVerdict,
    val basesNumericallyIndistinguishable: Boolean,
    val betterPredictingBasis: MatrixBasisLabel?,
    val evidenceLevels: List<DualBasisEvidenceLevel>,
    val reason: String,
)

private fun assessOneBasis(
    label: MatrixBasisLabel,
    basis: CameraCoordinateBasis,
    observed: SensorToBufferMatrix3,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
): BasisMatrixAssessment {
    val geometry =
        assessWholeActiveArrayGeometry(
            matrix = observed,
            sourceLeftPx = basis.rect.leftPx,
            sourceTopPx = basis.rect.topPx,
            sourceWidthPx = basis.rect.widthPx,
            sourceHeightPx = basis.rect.heightPx,
            bufferWidthPx = bufferWidthPx,
            bufferHeightPx = bufferHeightPx,
        )
    val predicted = predictCameraX142SensorToBufferMatrix(basis.rect, bufferWidthPx, bufferHeightPx)
    if (predicted == null) {
        return BasisMatrixAssessment(
            basisLabel = label, basis = basis, geometry = geometry,
            predicted = null, coefficientResiduals = null, maxAbsCoefficientResidual = null,
            maxMappedPointResidualPx = null, matchesCameraX142Model = null,
        )
    }
    val p = predicted.matrix
    val residuals =
        listOf(
            observed.m00 - p.m00, observed.m01 - p.m01, observed.m02 - p.m02,
            observed.m10 - p.m10, observed.m11 - p.m11, observed.m12 - p.m12,
            observed.m20 - p.m20, observed.m21 - p.m21, observed.m22 - p.m22,
        )
    val rect = basis.rect
    val samplePoints =
        listOf(
            rect.leftPx.toDouble() to rect.topPx.toDouble(),
            rect.rightPx.toDouble() to rect.topPx.toDouble(),
            rect.leftPx.toDouble() to rect.bottomPx.toDouble(),
            rect.rightPx.toDouble() to rect.bottomPx.toDouble(),
            (rect.leftPx + rect.rightPx) / 2.0 to (rect.topPx + rect.bottomPx) / 2.0,
        )
    fun mapX(m: SensorToBufferMatrix3, x: Double, y: Double) = m.m00 * x + m.m01 * y + m.m02
    fun mapY(m: SensorToBufferMatrix3, x: Double, y: Double) = m.m10 * x + m.m11 * y + m.m12
    val maxPointResidual =
        samplePoints.maxOf { (x, y) ->
            val dx = mapX(observed, x, y) - mapX(p, x, y)
            val dy = mapY(observed, x, y) - mapY(p, x, y)
            max(abs(dx), abs(dy))
        }
    return BasisMatrixAssessment(
        basisLabel = label,
        basis = basis,
        geometry = geometry,
        predicted = predicted,
        coefficientResiduals = residuals,
        maxAbsCoefficientResidual = residuals.maxOf { abs(it) },
        maxMappedPointResidualPx = maxPointResidual,
        matchesCameraX142Model = maxPointResidual <= CAMERAX_142_MODEL_MATCH_TOLERANCE_PX,
    )
}

/**
 * Evaluates [observedMatrix] under both candidate bases. Either basis may be `null` (typed
 * unavailability — e.g. the opened logical camera could not be identified, recon §2.3 / task §3);
 * the comparison then degrades honestly rather than guessing: with exactly one basis it can still
 * report that basis's own match/mismatch, with none (or no matrix) it is
 * [DualBasisComparisonVerdict.INSUFFICIENT_INPUT].
 *
 * Note the mapped-point residual is evaluated only against affine predictions — the model itself only
 * ever produces axis-aligned affine matrices, and a genuinely projective *observed* matrix simply
 * yields large residuals against them (its geometry classification separately reports
 * [WholeActiveArrayGeometryClass.SHEAR_OR_PERSPECTIVE]; the comparison here treats affine map
 * differences without a perspective divide, which is exact for every affine observed matrix and a
 * conservative lower bound otherwise).
 */
internal fun assessDualBasisMatrixEvidence(
    observedMatrix: SensorToBufferMatrix3?,
    logicalBasis: CameraCoordinateBasis?,
    physicalBasis: CameraCoordinateBasis?,
    bufferWidthPx: Int?,
    bufferHeightPx: Int?,
): DualBasisMatrixEvidence {
    val baseLevels = listOf(DualBasisEvidenceLevel.API_DECLARED_DOMAIN, DualBasisEvidenceLevel.FRAME_CONTENT_CORRESPONDENCE_UNMEASURED)
    if (observedMatrix == null || bufferWidthPx == null || bufferHeightPx == null ||
        bufferWidthPx <= 0 || bufferHeightPx <= 0 || (logicalBasis == null && physicalBasis == null)
    ) {
        return DualBasisMatrixEvidence(
            observedMatrix = observedMatrix,
            observedTransformClass = observedMatrix?.let { classifySensorToBufferMatrix(it) },
            bufferWidthPx = bufferWidthPx, bufferHeightPx = bufferHeightPx,
            logical = null, physical = null,
            comparisonVerdict = DualBasisComparisonVerdict.INSUFFICIENT_INPUT,
            basesNumericallyIndistinguishable = false,
            betterPredictingBasis = null,
            evidenceLevels = baseLevels,
            reason = "no observed matrix, no valid buffer dimensions, or neither candidate basis captured — " +
                "no dual-basis assessment attempted",
        )
    }

    val logical = logicalBasis?.let { assessOneBasis(MatrixBasisLabel.LOGICAL_OPENED_CAMERA_BASIS, it, observedMatrix, bufferWidthPx, bufferHeightPx) }
    val physical = physicalBasis?.let { assessOneBasis(MatrixBasisLabel.SELECTED_PHYSICAL_CAMERA_BASIS, it, observedMatrix, bufferWidthPx, bufferHeightPx) }

    val logicalMatches = logical?.matchesCameraX142Model == true
    val physicalMatches = physical?.matchesCameraX142Model == true
    val rectsEqual = logicalBasis != null && physicalBasis != null && logicalBasis.rect == physicalBasis.rect

    val verdict =
        when {
            logicalMatches && physicalMatches -> DualBasisComparisonVerdict.MATCHES_BOTH_BASES_NUMERICALLY_INDISTINGUISHABLE
            logicalMatches -> DualBasisComparisonVerdict.MATCHES_LOGICAL_BASIS_ONLY
            physicalMatches -> DualBasisComparisonVerdict.MATCHES_PHYSICAL_BASIS_ONLY
            else -> DualBasisComparisonVerdict.MATCHES_NEITHER_BASIS
        }

    val logicalResidual = logical?.maxMappedPointResidualPx
    val physicalResidual = physical?.maxMappedPointResidualPx
    val betterBasis =
        when {
            logicalResidual != null && physicalResidual != null && logicalResidual < physicalResidual ->
                MatrixBasisLabel.LOGICAL_OPENED_CAMERA_BASIS
            logicalResidual != null && physicalResidual != null && physicalResidual < logicalResidual ->
                MatrixBasisLabel.SELECTED_PHYSICAL_CAMERA_BASIS
            else -> null
        }

    val levels = buildList {
        addAll(baseLevels)
        add(DualBasisEvidenceLevel.DEVICE_MATRIX_OBSERVED)
        if (logicalMatches || physicalMatches) add(DualBasisEvidenceLevel.CAMERAX_IMPLEMENTATION_MODEL_MATCH)
    }

    val reason =
        buildString {
            append("dual-basis assessment: verdict=${verdict.name}")
            if (verdict == DualBasisComparisonVerdict.MATCHES_BOTH_BASES_NUMERICALLY_INDISTINGUISHABLE) {
                append(
                    if (rectsEqual) {
                        " (both candidate rects are numerically equal — the matrix cannot distinguish " +
                            "them; this is NOT proof the bases are the same sensor, and NOT frame-content evidence)"
                    } else {
                        " (both bases matched within tolerance despite differing rects — inspect residuals)"
                    },
                )
            }
            append("; matrix-construction evidence only — frame-content correspondence unmeasured; ")
            append("no SensorToBufferDomainProof variant is constructed from this result")
        }

    return DualBasisMatrixEvidence(
        observedMatrix = observedMatrix,
        observedTransformClass = classifySensorToBufferMatrix(observedMatrix),
        bufferWidthPx = bufferWidthPx,
        bufferHeightPx = bufferHeightPx,
        logical = logical,
        physical = physical,
        comparisonVerdict = verdict,
        basesNumericallyIndistinguishable = rectsEqual && logicalMatches == physicalMatches,
        betterPredictingBasis = betterBasis,
        evidenceLevels = levels,
        reason = reason,
    )
}
