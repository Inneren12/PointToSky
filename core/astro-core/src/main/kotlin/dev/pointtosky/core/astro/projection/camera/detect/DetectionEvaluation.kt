package dev.pointtosky.core.astro.projection.camera.detect

import dev.pointtosky.core.astro.projection.camera.skylog.SkyPredictedStar
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * SKY-2: **evaluation metrics only.** Scores a detector run against a frame's predicted star positions
 * so a change to the detector can be judged by a number instead of by eye.
 *
 * ## This is not the matcher, and must never be quoted as one
 * The association below is a nearest-neighbour pairing inside a fixed pixel radius. It works only when
 * the predicted positions are already very nearly right — which is exactly the situation a synthetic
 * fixture creates and exactly the situation real data does not. The production matcher has to survive an
 * unknown rotation, an unknown translation, a pointing error of many pixels, missing stars, and
 * detections with no counterpart at all; it will be built from geometric invariants that do not care
 * where a star was predicted to land. Nothing here does any of that.
 *
 * So a number produced by this file may be reported as "the detector recovered N of M *known* sources at
 * this residual". It may never be reported as a match rate, a correspondence, a plate solution, or
 * evidence that pointing works. Feeding [DetectionEvaluationReport.matches] into a pose solve would be
 * assuming the answer: the pairing was made *from* the predicted positions, so a pose fitted to it can
 * only ever return the pose those predictions came from.
 *
 * ## Why greedy-by-distance rather than per-prediction nearest
 * Taking each prediction's nearest detection independently lets one detection be claimed by two
 * predictions, which inflates the detection rate on exactly the crowded fields where it should fall.
 * Sorting every in-tolerance pair by distance and consuming greedily gives a one-to-one assignment,
 * deterministically (ties break on the index pair), for a cost that is irrelevant at these list sizes.
 * It is not the globally optimal assignment — a Hungarian solve would be — but the difference only
 * appears in configurations where the association is already ambiguous, and a metric utility should not
 * be quietly resolving ambiguity that the real matcher will have to face honestly.
 */

/** A star whose position in analysis-buffer pixels is known, used as ground truth for scoring. */
data class PredictedPointPx(
    val catalogIndex: Int,
    val xPx: Double,
    val yPx: Double,
) {
    init {
        require(xPx.isFinite() && yPx.isFinite()) { "predicted point must be finite; was ($xPx, $yPx)" }
    }
}

/**
 * One prediction paired with one detection, both by index into the lists handed to
 * [evaluateDetections], plus the pixel distance between them.
 */
data class DetectionMatch(
    val predictedIndex: Int,
    val detectedIndex: Int,
    val residualPx: Double,
)

/**
 * The score of one detector run over one frame.
 *
 * @property detectionRate matched predictions as a fraction of all predictions, or `null` when there
 *   were no predictions to recover — which is a frame that cannot be scored, not a rate of zero.
 * @property centroidResidualRmsPx root-mean-square distance over [matches], or `null` when nothing
 *   matched. This is the number that says whether the centroid is sub-pixel; it is measured only over
 *   pairs the association accepted, so it says nothing about the sources it failed to pair.
 * @property falsePositiveCount detections left unpaired. On a synthetic frame every one is genuinely
 *   spurious. On a real frame it is an upper bound at best: the predicted set is limited by catalogue
 *   depth and by what the projector considered in view, so a real star the catalogue did not carry
 *   counts here as a false positive despite being a correct detection.
 */
data class DetectionEvaluationReport(
    val predictedCount: Int,
    val detectedCount: Int,
    val matchedCount: Int,
    val detectionRate: Double?,
    val centroidResidualRmsPx: Double?,
    val maxCentroidResidualPx: Double?,
    val falsePositiveCount: Int,
    val matches: List<DetectionMatch>,
)

/**
 * Scores [detections] against [predicted], pairing a detection with a prediction only when they lie
 * within [tolerancePx] of each other. See the file KDoc for what this may and may not be used to claim.
 */
fun evaluateDetections(
    detections: List<DetectedSource>,
    predicted: List<PredictedPointPx>,
    tolerancePx: Double,
): DetectionEvaluationReport {
    require(tolerancePx > 0.0 && tolerancePx.isFinite()) { "tolerancePx must be positive and finite; was $tolerancePx" }

    val candidates = mutableListOf<DetectionMatch>()
    predicted.forEachIndexed { predictedIndex, point ->
        detections.forEachIndexed { detectedIndex, detection ->
            val distance = hypot(detection.xPx - point.xPx, detection.yPx - point.yPx)
            if (distance <= tolerancePx) candidates.add(DetectionMatch(predictedIndex, detectedIndex, distance))
        }
    }

    val claimedPredictions = HashSet<Int>()
    val claimedDetections = HashSet<Int>()
    val matches = mutableListOf<DetectionMatch>()
    // Distance first; the index tie-breaks make the greedy walk reproducible when two pairs are exactly
    // equidistant, which synthetic symmetric fixtures produce readily.
    val ordered =
        candidates.sortedWith(
            compareBy<DetectionMatch> { it.residualPx }
                .thenBy { it.predictedIndex }
                .thenBy { it.detectedIndex },
        )
    for (candidate in ordered) {
        val bothFree =
            candidate.predictedIndex !in claimedPredictions && candidate.detectedIndex !in claimedDetections
        if (!bothFree) continue
        claimedPredictions.add(candidate.predictedIndex)
        claimedDetections.add(candidate.detectedIndex)
        matches.add(candidate)
    }

    val residuals = matches.map { it.residualPx }
    return DetectionEvaluationReport(
        predictedCount = predicted.size,
        detectedCount = detections.size,
        matchedCount = matches.size,
        detectionRate = if (predicted.isEmpty()) null else matches.size.toDouble() / predicted.size,
        centroidResidualRmsPx = residuals.rootMeanSquareOrNull(),
        maxCentroidResidualPx = residuals.maxOrNull(),
        falsePositiveCount = detections.size - matches.size,
        matches = matches.sortedBy { it.predictedIndex },
    )
}

/**
 * The subset of these recorded predictions that has an image-space position to score against.
 *
 * Stars with a `null` [SkyPredictedStar.imageXPx]/[SkyPredictedStar.imageYPx] were behind the camera —
 * a normal outcome, not a failure — and are dropped rather than counted as predictions the detector
 * failed to recover. Reading the *recorded* coordinates is correct here precisely because this is a
 * detection metric and not a projection check: `SkySessionLogReplay` already re-derives them from the
 * catalogue and diffs the two, so the projection is verified independently of anything measured here.
 */
fun List<SkyPredictedStar>.toPredictedPointsPx(): List<PredictedPointPx> =
    mapNotNull { star ->
        val x = star.imageXPx ?: return@mapNotNull null
        val y = star.imageYPx ?: return@mapNotNull null
        PredictedPointPx(catalogIndex = star.catalogIndex, xPx = x, yPx = y)
    }

private fun List<Double>.rootMeanSquareOrNull(): Double? {
    if (isEmpty()) return null
    return sqrt(sumOf { it * it } / size)
}
