package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun accepted(
    id: Int,
    residualPx: Double,
    region: PointRegion,
) = FrameContentPointResidual.Accepted(
    pointId = FrameContentPointId(0, id),
    observedXPx = 0.0,
    observedYPx = 0.0,
    predictedXPx = residualPx,
    predictedYPx = 0.0,
    dxPx = residualPx,
    dyPx = 0.0,
    euclideanResidualPx = residualPx,
    normalizedResidual = residualPx / 800.0,
    region = region,
)

/** Six points spanning CENTER/EDGE/CORNER — satisfies [MIN_WELL_DISTRIBUTED_POINTS_FOR_VERDICT] and
 * [MIN_DISTINCT_REGIONS_FOR_VERDICT] with the default thresholds. */
private fun wellDistributedResiduals(): List<FrameContentPointResidual> =
    listOf(
        accepted(0, 1.0, PointRegion.CENTER),
        accepted(1, 1.0, PointRegion.CENTER),
        accepted(2, 1.0, PointRegion.EDGE),
        accepted(3, 1.0, PointRegion.EDGE),
        accepted(4, 1.0, PointRegion.CORNER),
        accepted(5, 1.0, PointRegion.CORNER),
    )

private fun summary(
    id: FrameContentMappingHypothesisId,
    rms: Double?,
    cornerRms: Double? = rms,
) = FrameContentResidualSummary(
    hypothesisId = id,
    acceptedCount = 6,
    rejectedCounts = emptyMap(),
    rmsPx = rms,
    medianPx = rms,
    p95Px = rms,
    maxPx = rms,
    centerRmsPx = rms,
    edgeRmsPx = rms,
    cornerRmsPx = cornerRms,
)

class FrameContentVerdictTest {
    @Test
    fun `pose invalid always wins regardless of point count`() {
        val result =
            computeFrameContentVerdict(
                summaries = listOf(summary(FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH, 1.0)),
                wellDistributedResiduals = wellDistributedResiduals(),
                poseValid = false,
            )
        assertEquals(FrameContentVerdict.POSE_FIT_INVALID, result.verdict)
    }

    @Test
    fun `too few points yields INSUFFICIENT_POINTS`() {
        val result =
            computeFrameContentVerdict(
                summaries =
                    listOf(
                        summary(FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH, 1.0),
                        summary(FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH, 5.0),
                    ),
                wellDistributedResiduals = listOf(accepted(0, 1.0, PointRegion.CENTER)),
                poseValid = true,
            )
        assertEquals(FrameContentVerdict.INSUFFICIENT_POINTS, result.verdict)
    }

    @Test
    fun `single region does not count as well-distributed even with enough points`() {
        val allCenter = (0 until 6).map { accepted(it, 1.0, PointRegion.CENTER) }
        val result =
            computeFrameContentVerdict(
                summaries =
                    listOf(
                        summary(FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH, 1.0),
                        summary(FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH, 5.0),
                    ),
                wellDistributedResiduals = allCenter,
                poseValid = true,
            )
        assertEquals(FrameContentVerdict.INSUFFICIENT_POINTS, result.verdict)
    }

    @Test
    fun `equal residuals across hypotheses are numerically indistinguishable`() {
        val result =
            computeFrameContentVerdict(
                summaries =
                    listOf(
                        summary(FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH, 2.0),
                        summary(FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH, 2.0),
                    ),
                wellDistributedResiduals = wellDistributedResiduals(),
                poseValid = true,
            )
        assertEquals(FrameContentVerdict.CONDITIONAL_PATHS_NUMERICALLY_INDISTINGUISHABLE, result.verdict)
        assertNull(result.lowerResidualHypothesisId)
    }

    @Test
    fun `residuals within the effective margin are indistinguishable, not a clean win`() {
        val result =
            computeFrameContentVerdict(
                summaries =
                    listOf(
                        summary(FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH, 2.0),
                        summary(FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH, 2.5),
                    ),
                wellDistributedResiduals = wellDistributedResiduals(),
                poseValid = true,
                thresholds = FrameContentVerdictThresholds(minAbsolutePixelMarginPx = 1.0, detectionNoiseMarginPx = 0.75),
            )
        assertEquals(FrameContentVerdict.CONDITIONAL_PATHS_NUMERICALLY_INDISTINGUISHABLE, result.verdict)
    }

    /**
     * The epistemic-correctness regression test the fix task requires: a physical-anchored synthetic
     * fixture (PHYSICAL_ACTIVE_ARRAY_MODEL_PATH has the clearly lower RMS, exactly the scenario an
     * earlier revision reported as a semantic "PHYSICAL_PATH_BETTER" proof-like verdict) must NOT
     * produce that claim merely because the pose was fit under the physical path. The enum no longer
     * even contains a `PHYSICAL_PATH_BETTER`/`LOGICAL_PATH_BETTER` value — this test locks that
     * structurally, then checks the conditional replacement's own honesty properties.
     */
    @Test
    fun `a clear physical-anchored residual difference is reported as conditional, never a path-winner proof`() {
        val result =
            computeFrameContentVerdict(
                summaries =
                    listOf(
                        summary(FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH, 20.0),
                        summary(FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH, 2.0),
                    ),
                wellDistributedResiduals = wellDistributedResiduals(),
                poseValid = true,
            )
        assertEquals(FrameContentVerdict.CONDITIONAL_RESIDUALS_DIFFER, result.verdict)
        // The enum's own declared values never include anything claiming a path is definitively better.
        assertTrue(FrameContentVerdict.values().none { it.name.endsWith("_PATH_BETTER") })
        // The lower-residual hypothesis is exported for ranking only, alongside the explicit caveat.
        assertEquals(FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH, result.lowerResidualHypothesisId)
        assertEquals(CROSS_HYPOTHESIS_RESIDUAL_INTERPRETATION, result.residualInterpretation)
        assertTrue(result.reason.contains("NOT an independent frame-content-basis verdict"))
        assertTrue(result.reason.contains(FRAME_CONTENT_STRONGER_EXPERIMENT_REQUIRED))
        assertEquals(false, result.independentPoseReferenceAvailable)
    }

    @Test
    fun `a clear logical-favoring residual difference is still only conditional, never LOGICAL_PATH_BETTER`() {
        val result =
            computeFrameContentVerdict(
                summaries =
                    listOf(
                        summary(FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH, 2.0),
                        summary(FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH, 20.0),
                    ),
                wellDistributedResiduals = wellDistributedResiduals(),
                poseValid = true,
            )
        assertEquals(FrameContentVerdict.CONDITIONAL_RESIDUALS_DIFFER, result.verdict)
        assertEquals(FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH, result.lowerResidualHypothesisId)
    }

    @Test
    fun `fewer than two usable hypotheses is mixed or inconclusive`() {
        val result =
            computeFrameContentVerdict(
                summaries = listOf(summary(FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH, 2.0)),
                wellDistributedResiduals = wellDistributedResiduals(),
                poseValid = true,
            )
        assertEquals(FrameContentVerdict.MIXED_OR_INCONCLUSIVE, result.verdict)
    }

    @Test
    fun `disagreement between overall RMS and corner RMS is reported as mixed`() {
        val result =
            computeFrameContentVerdict(
                summaries =
                    listOf(
                        // Overall favors PHYSICAL, but corners favor LOGICAL by more than the margin.
                        summary(FrameContentMappingHypothesisId.PHYSICAL_ACTIVE_ARRAY_MODEL_PATH, rms = 2.0, cornerRms = 20.0),
                        summary(FrameContentMappingHypothesisId.LOGICAL_CAMERAX_MATRIX_PATH, rms = 5.0, cornerRms = 1.0),
                    ),
                wellDistributedResiduals = wellDistributedResiduals(),
                poseValid = true,
            )
        assertEquals(FrameContentVerdict.MIXED_OR_INCONCLUSIVE, result.verdict)
    }
}
