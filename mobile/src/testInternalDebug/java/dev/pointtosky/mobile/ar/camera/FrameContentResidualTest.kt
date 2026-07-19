package dev.pointtosky.mobile.ar.camera

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun detected(
    row: Int,
    col: Int,
    x: Double,
    y: Double,
    bufferWidthPx: Int = 640,
    bufferHeightPx: Int = 480,
) = DetectedTargetPoint(
    pointId = FrameContentPointId(row, col),
    bufferXPx = x,
    bufferYPx = y,
    confidence = 1.0,
    refinementStatus = CornerRefinementStatus.SUBPIXEL_REFINED,
    region = classifyPointRegion(x, y, bufferWidthPx, bufferHeightPx),
)

class FrameContentResidualTest {
    @Test
    fun `identical predicted and observed points produce zero residual`() {
        val observed = detected(0, 0, 320.0, 240.0)
        val predicted = FrameContentProjectionOutcome.Projected(320.0, 240.0, 500.0)
        val residual = computePointResidual(observed, predicted, 640, 480)
        require(residual is FrameContentPointResidual.Accepted)
        assertEquals(0.0, residual.dxPx)
        assertEquals(0.0, residual.dyPx)
        assertEquals(0.0, residual.euclideanResidualPx)
        assertEquals(0.0, residual.normalizedResidual)
    }

    @Test
    fun `known translation error is reported exactly`() {
        val observed = detected(0, 0, 100.0, 100.0)
        val predicted = FrameContentProjectionOutcome.Projected(103.0, 104.0, 500.0)
        val residual = computePointResidual(observed, predicted, 640, 480)
        require(residual is FrameContentPointResidual.Accepted)
        assertEquals(3.0, residual.dxPx)
        assertEquals(4.0, residual.dyPx)
        assertEquals(5.0, residual.euclideanResidualPx) // 3-4-5 triangle
    }

    @Test
    fun `known scale error grows toward the edges`() {
        // A predicted point set that is the observed set scaled by 1.05 about the buffer center: the
        // residual at a point must grow monotonically with that point's distance from the center.
        val bufferWidth = 640
        val bufferHeight = 480
        val centerX = bufferWidth / 2.0
        val centerY = bufferHeight / 2.0
        val scale = 1.05

        fun residualAt(
            x: Double,
            y: Double,
        ): Double {
            val observed = detected(0, 0, x, y, bufferWidth, bufferHeight)
            val predictedX = centerX + (x - centerX) * scale
            val predictedY = centerY + (y - centerY) * scale
            val predicted = FrameContentProjectionOutcome.Projected(predictedX, predictedY, 500.0)
            val residual = computePointResidual(observed, predicted, bufferWidth, bufferHeight)
            require(residual is FrameContentPointResidual.Accepted)
            return residual.euclideanResidualPx
        }

        val nearCenter = residualAt(centerX + 10.0, centerY)
        val midway = residualAt(centerX + 150.0, centerY)
        val nearEdge = residualAt(centerX + 300.0, centerY)

        assertTrue(nearCenter < midway, "residual must grow with distance from center: $nearCenter vs $midway")
        assertTrue(midway < nearEdge, "residual must grow with distance from center: $midway vs $nearEdge")
    }

    @Test
    fun `rejected outcome never contributes an Accepted residual`() {
        val observed = detected(0, 0, 320.0, 240.0)
        val predicted = FrameContentProjectionOutcome.Rejected(FrameContentPointRejectionReason.INVALID_HOMOGENEOUS_DIVISION)
        val residual = computePointResidual(observed, predicted, 640, 480)
        require(residual is FrameContentPointResidual.Rejected)
        assertEquals(FrameContentPointRejectionReason.INVALID_HOMOGENEOUS_DIVISION, residual.reason)
    }

    @Test
    fun `summary excludes rejected points from RMS but counts them by reason`() {
        val residuals =
            listOf(
                FrameContentPointResidual.Accepted(
                    FrameContentPointId(0, 0), 0.0, 0.0, 3.0, 4.0, 3.0, 4.0, 5.0, 5.0 / sqrt(640.0 * 640 + 480.0 * 480), PointRegion.CENTER,
                ),
                FrameContentPointResidual.Rejected(FrameContentPointId(0, 1), FrameContentPointRejectionReason.BEHIND_CAMERA),
                FrameContentPointResidual.Rejected(FrameContentPointId(0, 2), FrameContentPointRejectionReason.BEHIND_CAMERA),
                FrameContentPointResidual.Rejected(FrameContentPointId(1, 0), FrameContentPointRejectionReason.OUTSIDE_BUFFER_BOUNDS),
            )
        val summary = summarizeResiduals(FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH, residuals)
        assertEquals(1, summary.acceptedCount)
        assertEquals(2, summary.rejectedCounts[FrameContentPointRejectionReason.BEHIND_CAMERA])
        assertEquals(1, summary.rejectedCounts[FrameContentPointRejectionReason.OUTSIDE_BUFFER_BOUNDS])
        assertEquals(5.0, summary.rmsPx)
        assertEquals(5.0, summary.medianPx)
    }

    @Test
    fun `RMS median and p95 match known hand-computed values`() {
        // Residuals: 1, 2, 3, 4, 10 (ascending). Median = 3. Mean-of-squares = (1+4+9+16+100)/5 = 26 -> rms = sqrt(26).
        val values = listOf(1.0, 2.0, 3.0, 4.0, 10.0)
        val residuals =
            values.mapIndexed { index, value ->
                FrameContentPointResidual.Accepted(
                    pointId = FrameContentPointId(0, index),
                    observedXPx = 0.0,
                    observedYPx = 0.0,
                    predictedXPx = value,
                    predictedYPx = 0.0,
                    dxPx = value,
                    dyPx = 0.0,
                    euclideanResidualPx = value,
                    normalizedResidual = value / 800.0,
                    region = PointRegion.CENTER,
                )
            }
        val summary = summarizeResiduals(FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH, residuals)
        assertEquals(sqrt(26.0), summary.rmsPx)
        assertEquals(3.0, summary.medianPx)
        assertEquals(10.0, summary.maxPx)
        // p95 (numpy linear interpolation, n=5): rank = 0.95*4 = 3.8 -> between index 3 (4.0) and 4 (10.0).
        assertTrue(kotlin.math.abs((4.0 + (10.0 - 4.0) * 0.8) - summary.p95Px!!) < 1e-9)
    }

    @Test
    fun `percentileOfSorted returns null for an empty list`() {
        assertNull(percentileOfSorted(emptyList(), 0.95))
    }

    @Test
    fun `even-count median averages the two middle values`() {
        val values = listOf(1.0, 2.0, 3.0, 4.0)
        val residuals =
            values.mapIndexed { index, value ->
                FrameContentPointResidual.Accepted(
                    FrameContentPointId(0, index), 0.0, 0.0, value, 0.0, value, 0.0, value, value / 800.0, PointRegion.CENTER,
                )
            }
        val summary = summarizeResiduals(FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH, residuals)
        assertEquals(2.5, summary.medianPx)
    }
}
