package dev.pointtosky.mobile.ar.camera

import kotlin.math.abs

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only, task §6). An **evidence-only**
 * verdict comparing hypothesis residuals — never converted into a [SensorToBufferDomainProof], never
 * used to publish `AnalysisBuffer` intrinsics, never a claim that CAM-2c calibrated projection is
 * unblocked. See `FrameContentCorrespondenceSnapshot.kt`'s file KDoc for the explicit "not proof"
 * statement this experiment's report always carries.
 *
 * ## Epistemic correctness fix — no independent path-winner verdict
 * An earlier revision of this enum included `LOGICAL_PATH_BETTER`/`PHYSICAL_PATH_BETTER`/
 * `RECONCILED_PATH_BETTER`. Those were removed: this experiment's one frozen pose is fit *using*
 * [FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH]'s own camera matrix
 * ([FRAME_CONTENT_POSE_REFERENCE_HYPOTHESIS]) against the very correspondences every hypothesis's
 * residual is then measured against — a single-planar-target homography/pose solve can absorb part of
 * any real intrinsics/mapping discrepancy into its own reference hypothesis's favor, so a lower
 * residual for that hypothesis is expected *by construction*, not proof it is the better real-world
 * mapping model. This file therefore can only ever report a residual *difference*, explicitly labelled
 * [CROSS_HYPOTHESIS_RESIDUAL_INTERPRETATION] — never a semantic "X is better" claim. A stronger future
 * experiment would require either an independently-sourced pose/reference (never derived from any
 * hypothesis under comparison) or a multi-view/fixed-rig calibration procedure — not merely refitting
 * each hypothesis's own pose independently (which stays circular for the same reason, applied
 * symmetrically instead of asymmetrically).
 */
internal enum class FrameContentVerdict {
    INSUFFICIENT_POINTS,
    POSE_FIT_INVALID,

    /** The best and second-best hypothesis RMS residuals differ by less than the effective margin —
     * conditional on the physical-anchored pose, same as every other non-degenerate verdict here. */
    CONDITIONAL_PATHS_NUMERICALLY_INDISTINGUISHABLE,

    /** The best and second-best hypothesis RMS residuals differ by more than the effective margin.
     * [FrameContentVerdictResult.lowerResidualHypothesisId] names which hypothesis had the lower
     * residual, for ranking/visualization only — this is **not** a claim that hypothesis is the better
     * real-world mapping model (see this enum's own KDoc and
     * [FrameContentVerdictResult.residualInterpretation]). */
    CONDITIONAL_RESIDUALS_DIFFER,
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
    /** Populated only for [FrameContentVerdict.CONDITIONAL_RESIDUALS_DIFFER] — which hypothesis had the
     * lower RMS residual, for ranking/visualization only. Never read as "the better hypothesis": see
     * [residualInterpretation]. */
    val lowerResidualHypothesisId: FrameContentMappingHypothesisId? = null,
    /** Always [CROSS_HYPOTHESIS_RESIDUAL_INTERPRETATION] — carried on every result (not just
     * [FrameContentVerdict.CONDITIONAL_RESIDUALS_DIFFER]) so a reader never has to look elsewhere to
     * find the caveat that applies to this whole file's verdicts. */
    val residualInterpretation: String = CROSS_HYPOTHESIS_RESIDUAL_INTERPRETATION,
    /** Always `false` for this implementation — no code path in this experiment sources a pose from
     * anything other than [FRAME_CONTENT_POSE_REFERENCE_HYPOTHESIS]'s own camera matrix. Exported so a
     * reader/JSON consumer never has to infer this from the absence of a field. */
    val independentPoseReferenceAvailable: Boolean = false,
)

/** Fixed, exported description of what a *conclusive*, non-conditional path-winner verdict would
 * require — task §1's "document what stronger future experiment would be required." Never constructed
 * by this experiment; carried as a constant string so the report/JSON can state it without any reader
 * having to consult this file's KDoc. */
internal const val FRAME_CONTENT_STRONGER_EXPERIMENT_REQUIRED: String =
    "An independent path-winner verdict would require either (a) a pose/reference sourced " +
        "independently of every hypothesis under comparison (e.g. a fixed, externally-surveyed rig, or " +
        "a separately-calibrated reference camera), or (b) a multi-view calibration experiment (e.g. " +
        "Zhang's method over many frames/poses) that estimates intrinsics and pose jointly without " +
        "anchoring either to one candidate hypothesis. Independently refitting pose under each " +
        "hypothesis and comparing each hypothesis's own optimized residual is NOT sufficient — that " +
        "remains circular, just symmetrically instead of asymmetrically so."

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
            FrameContentVerdict.CONDITIONAL_PATHS_NUMERICALLY_INDISTINGUISHABLE,
            "Best RMS (${best.hypothesisId}=${best.rmsPx}) and second-best RMS " +
                "(${secondBest.hypothesisId}=${secondBest.rmsPx}) differ by ${abs(margin)}px, below the " +
                "effective margin of ${thresholds.effectiveMarginPx}px (max of minAbsolutePixelMarginPx=" +
                "${thresholds.minAbsolutePixelMarginPx} and detectionNoiseMarginPx=${thresholds.detectionNoiseMarginPx}). " +
                "$CROSS_HYPOTHESIS_RESIDUAL_INTERPRETATION.",
            thresholds,
        )
    }

    // Cross-check against corner-region RMS alone, when available for both leaders: a clean overall
    // winner that reverses in the corners (where geometric mapping errors are typically largest) is
    // reported as mixed rather than a clean residual-difference finding.
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
                    "single residual-difference finding is drawn.",
                thresholds,
            )
        }
    }

    // NEVER a semantic "X path is better" verdict (see this file's own KDoc): the lower-residual
    // hypothesis is exported only as a ranking field, always alongside the explicit conditional
    // interpretation and a pointer to what a real, non-circular experiment would require.
    return FrameContentVerdictResult(
        FrameContentVerdict.CONDITIONAL_RESIDUALS_DIFFER,
        "${best.hypothesisId} has the lower RMS residual (${best.rmsPx}px vs next-best " +
            "${secondBest.hypothesisId}=${secondBest.rmsPx}px), a margin of ${abs(margin)}px exceeding the " +
            "effective threshold of ${thresholds.effectiveMarginPx}px. $CROSS_HYPOTHESIS_RESIDUAL_INTERPRETATION: " +
            "the pose was fit using ${FRAME_CONTENT_POSE_REFERENCE_HYPOTHESIS}'s own camera matrix against " +
            "these same correspondences, so this difference is NOT an independent frame-content-basis " +
            "verdict — it is expected to favor ${FRAME_CONTENT_POSE_REFERENCE_HYPOTHESIS} by construction, " +
            "regardless of which hypothesis actually describes the real mapping. $FRAME_CONTENT_STRONGER_EXPERIMENT_REQUIRED",
        thresholds,
        lowerResidualHypothesisId = best.hypothesisId,
    )
}
