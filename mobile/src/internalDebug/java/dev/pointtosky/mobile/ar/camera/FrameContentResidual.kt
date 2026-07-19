package dev.pointtosky.mobile.ar.camera

import kotlin.math.sqrt

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only, task §5). Per-point and
 * per-hypothesis residual computation between a real [DetectedTargetPoint] (observed) and one
 * hypothesis's [FrameContentProjectionOutcome] (predicted).
 */
internal sealed interface FrameContentPointResidual {
    val pointId: FrameContentPointId

    data class Accepted(
        override val pointId: FrameContentPointId,
        val observedXPx: Double,
        val observedYPx: Double,
        val predictedXPx: Double,
        val predictedYPx: Double,
        val dxPx: Double,
        val dyPx: Double,
        val euclideanResidualPx: Double,
        val normalizedResidual: Double,
        val region: PointRegion,
    ) : FrameContentPointResidual

    data class Rejected(
        override val pointId: FrameContentPointId,
        val reason: FrameContentPointRejectionReason,
    ) : FrameContentPointResidual
}

/**
 * Computes one point's residual (task §5): [observed] must already carry a resolved
 * [DetectedTargetPoint.pointId]. [predicted] is this hypothesis's [FrameContentProjectionOutcome] for
 * the same point. Never discards a rejected outcome — it becomes a typed [FrameContentPointResidual.Rejected]
 * rather than being silently dropped from the caller's list.
 */
internal fun computePointResidual(
    observed: DetectedTargetPoint,
    predicted: FrameContentProjectionOutcome,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
): FrameContentPointResidual =
    when (predicted) {
        is FrameContentProjectionOutcome.Rejected ->
            FrameContentPointResidual.Rejected(observed.pointId, predicted.reason)
        is FrameContentProjectionOutcome.Projected -> {
            val dx = predicted.xPx - observed.bufferXPx
            val dy = predicted.yPx - observed.bufferYPx
            val euclidean = sqrt(dx * dx + dy * dy)
            val diagonal = sqrt(bufferWidthPx.toDouble() * bufferWidthPx + bufferHeightPx.toDouble() * bufferHeightPx)
            FrameContentPointResidual.Accepted(
                pointId = observed.pointId,
                observedXPx = observed.bufferXPx,
                observedYPx = observed.bufferYPx,
                predictedXPx = predicted.xPx,
                predictedYPx = predicted.yPx,
                dxPx = dx,
                dyPx = dy,
                euclideanResidualPx = euclidean,
                normalizedResidual = if (diagonal > 0.0) euclidean / diagonal else Double.NaN,
                region = observed.region,
            )
        }
    }

/** Per-hypothesis residual summary (task §5): every count/statistic is computed **only** from
 * [FrameContentPointResidual.Accepted] entries; rejected points are counted by reason
 * ([rejectedCounts]) but never folded into any RMS/median/p95/max figure. */
internal data class FrameContentResidualSummary(
    val hypothesisId: FrameContentMappingHypothesisId,
    val acceptedCount: Int,
    val rejectedCounts: Map<FrameContentPointRejectionReason, Int>,
    val rmsPx: Double?,
    val medianPx: Double?,
    val p95Px: Double?,
    val maxPx: Double?,
    val centerRmsPx: Double?,
    val edgeRmsPx: Double?,
    val cornerRmsPx: Double?,
)

/** Linear-interpolation percentile (numpy's default convention) over a **pre-sorted ascending** list —
 * callers must sort first; this function does not re-sort, so it can be reused for both the full set
 * and a region-filtered subset without repeated work. */
internal fun percentileOfSorted(
    sortedAscending: List<Double>,
    percentile: Double,
): Double? {
    if (sortedAscending.isEmpty()) return null
    require(percentile in 0.0..1.0) { "percentile must be within [0, 1]; was $percentile" }
    if (sortedAscending.size == 1) return sortedAscending[0]
    val rank = percentile * (sortedAscending.size - 1)
    val lowerIndex = rank.toInt()
    val upperIndex = (lowerIndex + 1).coerceAtMost(sortedAscending.size - 1)
    val fraction = rank - lowerIndex
    return sortedAscending[lowerIndex] + (sortedAscending[upperIndex] - sortedAscending[lowerIndex]) * fraction
}

private fun rms(values: List<Double>): Double? {
    if (values.isEmpty()) return null
    return sqrt(values.sumOf { it * it } / values.size)
}

/** Summarizes [residuals] for one hypothesis (task §5). */
internal fun summarizeResiduals(
    hypothesisId: FrameContentMappingHypothesisId,
    residuals: List<FrameContentPointResidual>,
): FrameContentResidualSummary {
    val accepted = residuals.filterIsInstance<FrameContentPointResidual.Accepted>()
    val rejected = residuals.filterIsInstance<FrameContentPointResidual.Rejected>()
    val rejectedCounts = rejected.groupingBy { it.reason }.eachCount()

    val sortedResidualsPx = accepted.map { it.euclideanResidualPx }.sorted()
    val median =
        if (sortedResidualsPx.isEmpty()) {
            null
        } else if (sortedResidualsPx.size % 2 == 1) {
            sortedResidualsPx[sortedResidualsPx.size / 2]
        } else {
            (sortedResidualsPx[sortedResidualsPx.size / 2 - 1] + sortedResidualsPx[sortedResidualsPx.size / 2]) / 2.0
        }

    return FrameContentResidualSummary(
        hypothesisId = hypothesisId,
        acceptedCount = accepted.size,
        rejectedCounts = rejectedCounts,
        rmsPx = rms(accepted.map { it.euclideanResidualPx }),
        medianPx = median,
        p95Px = percentileOfSorted(sortedResidualsPx, 0.95),
        maxPx = sortedResidualsPx.lastOrNull(),
        centerRmsPx = rms(accepted.filter { it.region == PointRegion.CENTER }.map { it.euclideanResidualPx }),
        edgeRmsPx = rms(accepted.filter { it.region == PointRegion.EDGE }.map { it.euclideanResidualPx }),
        cornerRmsPx = rms(accepted.filter { it.region == PointRegion.CORNER }.map { it.euclideanResidualPx }),
    )
}
