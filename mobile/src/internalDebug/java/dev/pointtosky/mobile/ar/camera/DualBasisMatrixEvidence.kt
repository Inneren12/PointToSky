package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import dev.pointtosky.core.astro.projection.camera.classifySensorToBufferMatrix
import kotlin.math.abs
import kotlin.math.hypot

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
 * A [CameraX142ModelComparison.MATCHES_MODEL] outcome means: the observed matrix is **inside the
 * model's own structural scope** (axis-aligned, affine bottom row ≈ `[0,0,1]`, no off-diagonal
 * rotation/shear, positive non-degenerate scale — decided by the same classifier the geometry
 * assessment uses) AND its mapped corners/center reproduce, within
 * [CAMERAX_142_MODEL_MATCH_TOLERANCE_PX], the matrix CameraX 1.4.2 *would construct* from that
 * basis's rect and this buffer. A projective or sheared matrix whose top two rows resemble the
 * prediction is typed [CameraX142ModelComparison.COMPARISON_UNSUPPORTED_STRUCTURE] — never a match.
 * Matching is a statement about matrix **metadata matching its own traced construction** — it is
 * **not** a measurement of what the HAL placed in the image. Frame-content correspondence remains
 * unmeasured ([DualBasisEvidenceLevel.FRAME_CONTENT_CORRESPONDENCE_UNMEASURED] is always attached),
 * and no result here may feed [SensorToBufferDomainProof] or unlock calibrated `AnalysisBuffer`
 * intrinsics.
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
 * `internalDebug`-only. Typed outcome of comparing one observed matrix against the CameraX 1.4.2
 * implementation model under one basis (P1 fix: model matching is only *defined* inside the model's
 * own structural scope — axis-aligned, affine, positive non-degenerate scale. A projective or
 * sheared observed matrix whose top two rows happen to resemble the predicted affine coefficients
 * must never be reported as a model match: the model can never produce such a matrix, and comparing
 * only the upper rows would silently ignore the very terms that make it unsupported).
 */
internal enum class CameraX142ModelComparison {
    /** Structure is inside the model's scope AND the mapped-point residual is within
     * [CAMERAX_142_MODEL_MATCH_TOLERANCE_PX]. */
    MATCHES_MODEL,

    /** Structure is inside the model's scope but the residual exceeds tolerance. */
    DIFFERS_FROM_MODEL,

    /** The observed matrix's structure is outside the model's scope (projective/sheared bottom-row
     * or off-diagonal terms, degenerate ≈0 scale, or negative/mirrored scale) — the comparison is
     * typed unsupported. Coefficient residuals stay available diagnostically (they expose exactly
     * which term is out of scope), but no mapped-point residual is computed and this can never count
     * as a model match or emit [DualBasisEvidenceLevel.CAMERAX_IMPLEMENTATION_MODEL_MATCH]. */
    COMPARISON_UNSUPPORTED_STRUCTURE,

    /** No prediction was computable for this basis/buffer (typed absence). */
    COMPARISON_UNAVAILABLE,
}

/**
 * `internalDebug`-only. One basis's full assessment: the fully-qualified basis identity, the geometry
 * classification of the observed matrix under that basis, the CameraX 1.4.2 model prediction for that
 * basis, and observed-vs-predicted residuals.
 *
 * @property coefficientResiduals `observed − predicted`, in [SensorToBufferMatrix3] order
 *   (`m00, m01, m02, m10, m11, m12, m20, m21, m22`); `null` when no prediction was computable.
 *   Computed for *every* structure (including unsupported ones — all nine terms, so a projective
 *   `m20`/`m22` deviation is visible in the residuals themselves).
 * @property maxAbsCoefficientResidual max of `|coefficientResiduals|`.
 * @property maxMappedPointResidualPx max **Euclidean** distance (`hypot(dx, dy)`) between
 *   observed-mapped and predicted-mapped points over the basis rect's four corners and center.
 *   Non-`null` only when [modelComparison] is [CameraX142ModelComparison.MATCHES_MODEL] or
 *   [CameraX142ModelComparison.DIFFERS_FROM_MODEL] — both matrices are then plain axis-aligned
 *   affine maps, where the comparison is exact. It is deliberately **not** computed for
 *   [CameraX142ModelComparison.COMPARISON_UNSUPPORTED_STRUCTURE]: applying only the upper rows of a
 *   projective matrix is not that matrix's actual mapping, and this codebase does not implement
 *   general projective matching because the pinned CameraX model itself is axis-aligned affine.
 * @property modelComparison the typed comparison outcome — see [CameraX142ModelComparison].
 * @property matchesCameraX142Model derived convenience: `true` iff [modelComparison] is
 *   [CameraX142ModelComparison.MATCHES_MODEL]; `false` for `DIFFERS_FROM_MODEL` **and**
 *   `COMPARISON_UNSUPPORTED_STRUCTURE`; `null` for `COMPARISON_UNAVAILABLE`. Metadata-construction
 *   evidence only — see file KDoc.
 */
internal data class BasisMatrixAssessment(
    val basisLabel: MatrixBasisLabel,
    val basis: CameraCoordinateBasis,
    val geometry: WholeActiveArrayGeometryAssessment,
    val predicted: CameraX142PredictedSensorToBuffer?,
    val coefficientResiduals: List<Double>?,
    val maxAbsCoefficientResidual: Double?,
    val maxMappedPointResidualPx: Double?,
    val modelComparison: CameraX142ModelComparison,
) {
    val matchesCameraX142Model: Boolean?
        get() =
            when (modelComparison) {
                CameraX142ModelComparison.MATCHES_MODEL -> true
                CameraX142ModelComparison.DIFFERS_FROM_MODEL,
                CameraX142ModelComparison.COMPARISON_UNSUPPORTED_STRUCTURE,
                -> false
                CameraX142ModelComparison.COMPARISON_UNAVAILABLE -> null
            }
}

/** Typed comparison outcome across the two bases (P2 fix: a dual match is only "numerically
 * indistinguishable" when the two candidate native rects are actually **equal** — two *differing*
 * rects that both land inside the match tolerance are an ambiguous dual match, a different and more
 * suspicious situation that must never be reported under the equal-rects name). */
internal enum class DualBasisComparisonVerdict {
    /** Only the opened logical camera's active-array basis reproduces the CameraX 1.4.2 model. */
    MATCHES_LOGICAL_BASIS_ONLY,

    /** Only the selected physical camera's active-array basis reproduces it. */
    MATCHES_PHYSICAL_BASIS_ONLY,

    /** Both bases reproduce the model AND the two candidate native rects are numerically **equal** —
     * the two models are then the same model, and no matrix can tell the bases apart. Never to be
     * read as "proven equal sensors" or as identifying which sensor produced frame content. */
    MATCHES_BOTH_EQUAL_RECTS_NUMERICALLY_INDISTINGUISHABLE,

    /** Both bases reproduce the model although their candidate native rects **differ** — an
     * ambiguous dual match (e.g. two rects so close that both predictions land inside the match
     * tolerance). Inspect the residuals; never reported as "indistinguishable". */
    MATCHES_BOTH_DIFFERING_RECTS_WITHIN_TOLERANCE,

    /** Neither basis reproduces the model — the observed matrix is not explained by the traced
     * CameraX 1.4.2 construction under either candidate rect (e.g. the historical 1.3.4 identity
     * matrix, an out-of-scope projective/sheared structure, or an untraced OEM behavior). */
    MATCHES_NEITHER_BASIS,

    /** The comparison could not run: no matrix observed, or neither basis could be captured. */
    INSUFFICIENT_INPUT,
}

/**
 * Pure verdict decision (P2 fix), extracted so the equal-rects/differing-rects split is directly
 * unit-testable: with the pinned CameraX 1.4.2 model and [CAMERAX_142_MODEL_MATCH_TOLERANCE_PX], two
 * *integer* rects that differ always shift the prediction by ≥ ~0.015 px at active-array
 * magnitudes, so [DualBasisComparisonVerdict.MATCHES_BOTH_DIFFERING_RECTS_WITHIN_TOLERANCE] is not
 * reachable from realistic integer fixtures — but a real device/HAL is not bound by that arithmetic,
 * and the verdict must stay honest if it ever occurs. `rectsEqual` must be the pure rect-identity
 * fact, never "both happened to match".
 */
internal fun dualBasisComparisonVerdict(
    logicalMatches: Boolean,
    physicalMatches: Boolean,
    rectsEqual: Boolean,
): DualBasisComparisonVerdict =
    when {
        logicalMatches && physicalMatches && rectsEqual ->
            DualBasisComparisonVerdict.MATCHES_BOTH_EQUAL_RECTS_NUMERICALLY_INDISTINGUISHABLE
        logicalMatches && physicalMatches ->
            DualBasisComparisonVerdict.MATCHES_BOTH_DIFFERING_RECTS_WITHIN_TOLERANCE
        logicalMatches -> DualBasisComparisonVerdict.MATCHES_LOGICAL_BASIS_ONLY
        physicalMatches -> DualBasisComparisonVerdict.MATCHES_PHYSICAL_BASIS_ONLY
        else -> DualBasisComparisonVerdict.MATCHES_NEITHER_BASIS
    }

/**
 * `internalDebug`-only. Residuals whose difference is at or below this bound are a **tie** for
 * [DualBasisMatrixEvidence.betterPredictingBasis] purposes (P2 fix: a raw `<` on doubles would rank
 * two bases whose residuals differ by float-rounding dust). `1e-6` px is far below every meaningful
 * residual scale in this diagnostic (float32 storage noise alone is ~1e-4 px at active-array
 * magnitudes) — anything closer than this is not a real ranking signal.
 */
internal const val DUAL_BASIS_RESIDUAL_TIE_TOLERANCE_PX: Double = 1e-6

/**
 * `internalDebug`-only. The full dual-basis evidence result for one observed matrix + buffer.
 *
 * @property logical the logical-basis assessment, `null` when the opened logical camera's basis was
 *   unavailable (typed reason in [reason]) — never silently substituted by the physical basis.
 * @property physical the physical-basis assessment, `null` symmetrically.
 * @property basesNumericallyIndistinguishable `true` **exactly when both bases were captured and
 *   their complete candidate native rects are numerically equal** — a pure rect-identity statement
 *   (the two candidate models are then one and the same model), independent of whether the observed
 *   matrix happened to match, differ, or be structurally unsupported. Never "proven equal sensors".
 * @property betterPredictingBasis whichever basis has the smaller
 *   [BasisMatrixAssessment.maxMappedPointResidualPx] by more than
 *   [DUAL_BASIS_RESIDUAL_TIE_TOLERANCE_PX] (recon fixture 16) — `null` on a tie (within that
 *   documented tolerance), when only one assessment exists, or when residuals are unavailable. A
 *   ranking, not a proof.
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

/**
 * `true` when the observed matrix's structure is inside the CameraX 1.4.2 model's own scope —
 * axis-aligned, affine, positive non-degenerate scale — as decided by the **same** classifier the
 * geometry assessment uses (never a second, drifting rule set): every structural reject
 * ([WholeActiveArrayGeometryClass.SHEAR_OR_PERSPECTIVE], [WholeActiveArrayGeometryClass.DEGENERATE],
 * and the negative/mirrored-scale [WholeActiveArrayGeometryClass.UNEXPLAINED] arm) leaves
 * [WholeActiveArrayGeometryAssessment.mappedBoundsPx] `null`, while every axis-aligned
 * positive-scale outcome (including numeric grey zones) carries mapped bounds. [assessOneBasis]
 * additionally requires valid geometry inputs, which the caller guarantees by construction.
 */
private fun WholeActiveArrayGeometryAssessment.isWithinCameraX142ModelStructuralScope(): Boolean =
    mappedBoundsPx != null

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
            maxMappedPointResidualPx = null, modelComparison = CameraX142ModelComparison.COMPARISON_UNAVAILABLE,
        )
    }
    val p = predicted.matrix
    // All nine coefficient residuals are always computed — for an unsupported structure they are the
    // diagnostic that shows exactly which term (e.g. m20/m22) is outside the model's scope.
    val residuals =
        listOf(
            observed.m00 - p.m00, observed.m01 - p.m01, observed.m02 - p.m02,
            observed.m10 - p.m10, observed.m11 - p.m11, observed.m12 - p.m12,
            observed.m20 - p.m20, observed.m21 - p.m21, observed.m22 - p.m22,
        )
    val maxAbsCoefficientResidual = residuals.maxOf { abs(it) }

    // P1 structural gate: the model only ever produces axis-aligned positive-scale affine matrices,
    // so an observed matrix outside that scope can never "match the model" — regardless of how much
    // its top two rows resemble the prediction. No mapped-point residual is computed here: applying
    // only the upper rows of a projective matrix is not that matrix's actual mapping, and general
    // projective matching is deliberately not implemented (the pinned model cannot need it).
    if (!geometry.isWithinCameraX142ModelStructuralScope()) {
        return BasisMatrixAssessment(
            basisLabel = label,
            basis = basis,
            geometry = geometry,
            predicted = predicted,
            coefficientResiduals = residuals,
            maxAbsCoefficientResidual = maxAbsCoefficientResidual,
            maxMappedPointResidualPx = null,
            modelComparison = CameraX142ModelComparison.COMPARISON_UNSUPPORTED_STRUCTURE,
        )
    }

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
    // Euclidean (hypot), matching the documented contract — both matrices are plain affine maps
    // here (the observed one passed the structural gate; the predicted one is affine by
    // construction), so this is the exact point-displacement between the two mappings.
    val maxPointResidual =
        samplePoints.maxOf { (x, y) ->
            val dx = mapX(observed, x, y) - mapX(p, x, y)
            val dy = mapY(observed, x, y) - mapY(p, x, y)
            hypot(dx, dy)
        }
    return BasisMatrixAssessment(
        basisLabel = label,
        basis = basis,
        geometry = geometry,
        predicted = predicted,
        coefficientResiduals = residuals,
        maxAbsCoefficientResidual = maxAbsCoefficientResidual,
        maxMappedPointResidualPx = maxPointResidual,
        modelComparison =
            if (maxPointResidual <= CAMERAX_142_MODEL_MATCH_TOLERANCE_PX) {
                CameraX142ModelComparison.MATCHES_MODEL
            } else {
                CameraX142ModelComparison.DIFFERS_FROM_MODEL
            },
    )
}

/**
 * Evaluates [observedMatrix] under both candidate bases. Either basis may be `null` (typed
 * unavailability — e.g. the opened logical camera could not be identified, recon §2.3 / task §3);
 * the comparison then degrades honestly rather than guessing: with exactly one basis it can still
 * report that basis's own match/mismatch, with none (or no matrix) it is
 * [DualBasisComparisonVerdict.INSUFFICIENT_INPUT].
 *
 * The mapped-point residual is computed only when the observed matrix is inside the model's own
 * axis-aligned affine structural scope (P1 fix — see [CameraX142ModelComparison]): a projective or
 * sheared observed matrix gets a typed
 * [CameraX142ModelComparison.COMPARISON_UNSUPPORTED_STRUCTURE] with coefficient residuals only, and
 * can never match the model or contribute the
 * [DualBasisEvidenceLevel.CAMERAX_IMPLEMENTATION_MODEL_MATCH] evidence level.
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

    val logicalMatches = logical?.modelComparison == CameraX142ModelComparison.MATCHES_MODEL
    val physicalMatches = physical?.modelComparison == CameraX142ModelComparison.MATCHES_MODEL
    // Pure rect identity — independent of any match outcome (P2 fix).
    val rectsEqual = logicalBasis != null && physicalBasis != null && logicalBasis.rect == physicalBasis.rect

    val verdict = dualBasisComparisonVerdict(logicalMatches, physicalMatches, rectsEqual)

    val logicalResidual = logical?.maxMappedPointResidualPx
    val physicalResidual = physical?.maxMappedPointResidualPx
    // Documented tie tolerance (P2 fix) — never a raw `<` that would rank float-rounding dust.
    val betterBasis =
        when {
            logicalResidual != null && physicalResidual != null &&
                logicalResidual < physicalResidual - DUAL_BASIS_RESIDUAL_TIE_TOLERANCE_PX ->
                MatrixBasisLabel.LOGICAL_OPENED_CAMERA_BASIS
            logicalResidual != null && physicalResidual != null &&
                physicalResidual < logicalResidual - DUAL_BASIS_RESIDUAL_TIE_TOLERANCE_PX ->
                MatrixBasisLabel.SELECTED_PHYSICAL_CAMERA_BASIS
            else -> null
        }

    val levels = buildList {
        addAll(baseLevels)
        add(DualBasisEvidenceLevel.DEVICE_MATRIX_OBSERVED)
        // Only a structurally-supported MATCHES_MODEL outcome may ever contribute this level (P1
        // fix) — an unsupported projective/sheared structure never reaches MATCHES_MODEL by
        // construction, so it can never appear here.
        if (logicalMatches || physicalMatches) add(DualBasisEvidenceLevel.CAMERAX_IMPLEMENTATION_MODEL_MATCH)
    }

    val reason =
        buildString {
            append("dual-basis assessment: verdict=${verdict.name}")
            when (verdict) {
                DualBasisComparisonVerdict.MATCHES_BOTH_EQUAL_RECTS_NUMERICALLY_INDISTINGUISHABLE ->
                    append(
                        " (both candidate rects are numerically equal — the matrix cannot distinguish " +
                            "them; this is NOT proof the bases are the same sensor, and NOT frame-content evidence)",
                    )
                DualBasisComparisonVerdict.MATCHES_BOTH_DIFFERING_RECTS_WITHIN_TOLERANCE ->
                    append(
                        " (both bases matched within tolerance despite DIFFERING rects — an ambiguous dual " +
                            "match, not indistinguishability; inspect the residuals)",
                    )
                else -> Unit
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
        basesNumericallyIndistinguishable = rectsEqual,
        betterPredictingBasis = betterBasis,
        evidenceLevels = levels,
        reason = reason,
    )
}
