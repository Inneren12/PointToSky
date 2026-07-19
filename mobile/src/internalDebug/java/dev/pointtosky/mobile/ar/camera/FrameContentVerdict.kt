package dev.pointtosky.mobile.ar.camera

import kotlin.math.abs

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only, task §6). An **evidence-only**
 * verdict comparing hypothesis residuals — never converted into a [SensorToBufferDomainProof], never
 * used to publish `AnalysisBuffer` intrinsics, never a claim that CAM-2c calibrated projection is
 * unblocked. See `FrameContentCorrespondenceSnapshot.kt`'s file KDoc for the explicit "not proof"
 * statement this experiment's report always carries.
 */
internal enum class FrameContentVerdict {
    INSUFFICIENT_POINTS,
    POSE_FIT_INVALID,
    LOGICAL_PATH_BETTER,
    PHYSICAL_PATH_BETTER,
    RECONCILED_PATH_BETTER,
    PATHS_NUMERICALLY_INDISTINGUISHABLE,
    MIXED_OR_INCONCLUSIVE,
}

/** Minimum number of *well-distributed* accepted points (task §7: "Require a minimum number of
 * well-distributed corners before allowing a conclusive verdict") — chosen as the smallest count that
 * can span all three [PointRegion] buckets with at least two points each, so a verdict is never drawn
 * from e.g. six points that are all clustered in the buffer center. */
internal const val MIN_WELL_DISTRIBUTED_POINTS_FOR_VERDICT: Int = 6

/** Minimum number of distinct [PointRegion] buckets ([MIN_WELL_DISTRIBUTED_POINTS_FOR_VERDICT] points
 * must span) — one region alone (e.g. all-center) is not "well-distributed" even if the point count is
 * high enough. */
internal const val MIN_DISTINCT_REGIONS_FOR_VERDICT: Int = 2

/** Minimum absolute pixel margin (task §6: "a minimum absolute pixel margin") two hypotheses' RMS
 * residuals must differ by before one is called meaningfully better — below this, a difference is
 * indistinguishable from ordinary integer-pixel rounding/quantization in the detector's own centroid
 * estimate, never a claim about *this* device's real detection noise. */
internal const val MIN_ABSOLUTE_PIXEL_MARGIN_PX: Double = 1.0

/** Default assumed detection/pose noise floor (task §6: "detection/pose noise") — the
 * [FrameContentCornerDetector]'s dot-centroid estimate and the planar-homography pose solve both carry
 * their own uncertainty; `0.75` px is a conservative placeholder based on this experiment's own
 * detector's minimum blob size, not a measured value from a real device (no device has run this
 * experiment yet). A caller may supply a measured value once real Pixel 9 sessions are available. */
internal const val DEFAULT_DETECTION_NOISE_MARGIN_PX: Double = 0.75

internal data class FrameContentVerdictThresholds(
    val minWellDistributedPoints: Int = MIN_WELL_DISTRIBUTED_POINTS_FOR_VERDICT,
    val minDistinctRegions: Int = MIN_DISTINCT_REGIONS_FOR_VERDICT,
    val minAbsolutePixelMarginPx: Double = MIN_ABSOLUTE_PIXEL_MARGIN_PX,
    val detectionNoiseMarginPx: Double = DEFAULT_DETECTION_NOISE_MARGIN_PX,
) {
    /** The margin actually applied: the larger of the two exported thresholds (task §6: "compare... the
     * difference between hypothesis residuals against both" — both must hold, so the effective margin
     * is their max). */
    val effectiveMarginPx: Double get() = maxOf(minAbsolutePixelMarginPx, detectionNoiseMarginPx)
}

internal data class FrameContentVerdictResult(
    val verdict: FrameContentVerdict,
    val reason: String,
    val thresholds: FrameContentVerdictThresholds,
)

private fun regionsRepresented(residuals: List<FrameContentPointResidual>): Set<PointRegion> =
    residuals.filterIsInstance<FrameContentPointResidual.Accepted>().map { it.region }.toSet()

/**
 * Computes the conservative verdict (task §6) from every hypothesis's [FrameContentResidualSummary] and
 * the raw per-hypothesis residual lists (needed to check region distribution independently of any one
 * hypothesis's own accepted set — [wellDistributedResiduals] should be the hypothesis with the most
 * accepted points, or any single representative hypothesis's list when they agree closely enough that
 * the choice does not matter; this experiment always passes [FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH]'s
 * list, the pose solver's own reference hypothesis). [poseValid] is `false` only when the frozen pose
 * solve itself failed or its own reprojection RMS could not be computed — checked before point count.
 */
internal fun computeFrameContentVerdict(
    summaries: List<FrameContentResidualSummary>,
    wellDistributedResiduals: List<FrameContentPointResidual>,
    poseValid: Boolean,
    thresholds: FrameContentVerdictThresholds = FrameContentVerdictThresholds(),
): FrameContentVerdictResult {
    if (!poseValid) {
        return FrameContentVerdictResult(
            FrameContentVerdict.POSE_FIT_INVALID,
            "The frozen pose solve failed or produced a non-finite reprojection RMS; no hypothesis " +
                "comparison is meaningful without a valid pose.",
            thresholds,
        )
    }

    val acceptedCount = wellDistributedResiduals.count { it is FrameContentPointResidual.Accepted }
    val distinctRegions = regionsRepresented(wellDistributedResiduals)
    if (acceptedCount < thresholds.minWellDistributedPoints || distinctRegions.size < thresholds.minDistinctRegions) {
        return FrameContentVerdictResult(
            FrameContentVerdict.INSUFFICIENT_POINTS,
            "Only $acceptedCount accepted point(s) across ${distinctRegions.size} region(s); need at least " +
                "${thresholds.minWellDistributedPoints} points spanning at least ${thresholds.minDistinctRegions} " +
                "regions (CENTER/EDGE/CORNER) before a conclusive verdict is allowed.",
            thresholds,
        )
    }

    val available = summaries.filter { it.rmsPx != null && it.rmsPx.isFinite() }
    if (available.size < 2) {
        return FrameContentVerdictResult(
            FrameContentVerdict.MIXED_OR_INCONCLUSIVE,
            "Fewer than two hypotheses produced a usable RMS residual (${available.size} available); no " +
                "pairwise comparison is possible.",
            thresholds,
        )
    }

    val ranked = available.sortedBy { it.rmsPx!! }
    val best = ranked[0]
    val secondBest = ranked[1]
    val margin = secondBest.rmsPx!! - best.rmsPx!!

    if (margin < thresholds.effectiveMarginPx) {
        return FrameContentVerdictResult(
            FrameContentVerdict.PATHS_NUMERICALLY_INDISTINGUISHABLE,
            "Best RMS (${best.hypothesisId}=${best.rmsPx}) and second-best RMS " +
                "(${secondBest.hypothesisId}=${secondBest.rmsPx}) differ by ${abs(margin)}px, below the " +
                "effective margin of ${thresholds.effectiveMarginPx}px (max of minAbsolutePixelMarginPx=" +
                "${thresholds.minAbsolutePixelMarginPx} and detectionNoiseMarginPx=${thresholds.detectionNoiseMarginPx}).",
            thresholds,
        )
    }

    // Cross-check against corner-region RMS alone, when available for both leaders: a clean overall
    // winner that reverses in the corners (where geometric mapping errors are typically largest) is
    // reported as mixed rather than a clean "better" verdict.
    val bestCornerRms = best.cornerRmsPx
    val secondCornerRms = secondBest.cornerRmsPx
    if (bestCornerRms != null && secondCornerRms != null && bestCornerRms.isFinite() && secondCornerRms.isFinite()) {
        val cornerMargin = secondCornerRms - bestCornerRms
        if (cornerMargin < -thresholds.effectiveMarginPx) {
            return FrameContentVerdictResult(
                FrameContentVerdict.MIXED_OR_INCONCLUSIVE,
                "Overall RMS favors ${best.hypothesisId}, but corner-region RMS favors " +
                    "${secondBest.hypothesisId} by more than the effective margin " +
                    "(${thresholds.effectiveMarginPx}px) — the two hypotheses disagree by region, so no " +
                    "single 'better' verdict is drawn.",
                thresholds,
            )
        }
    }

    val verdict =
        when (best.hypothesisId) {
            FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH -> FrameContentVerdict.LOGICAL_PATH_BETTER
            FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH -> FrameContentVerdict.PHYSICAL_PATH_BETTER
            FrameContentMappingHypothesisId.RECONCILED_PHYSICAL_TO_LOGICAL_PATH -> FrameContentVerdict.RECONCILED_PATH_BETTER
        }
    return FrameContentVerdictResult(
        verdict,
        "${best.hypothesisId} has the lowest RMS residual (${best.rmsPx}px vs next-best " +
            "${secondBest.hypothesisId}=${secondBest.rmsPx}px), a margin of ${abs(margin)}px exceeding the " +
            "effective threshold of ${thresholds.effectiveMarginPx}px.",
        thresholds,
    )
}
