package dev.pointtosky.core.astro.projection.camera.detect

import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarClassification
import dev.pointtosky.core.astro.projection.camera.skylog.SkyPredictedStar
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKY-2 evaluation-metric tests. These score the metric utility itself, not the detector: what it counts
 * as recovered, what it counts as spurious, and that it refuses to claim a rate it cannot compute.
 */
class DetectionEvaluationTest {
    private fun source(
        x: Double,
        y: Double,
        brightness: Double = 100.0,
    ): DetectedSource =
        DetectedSource(
            xPx = x,
            yPx = y,
            brightness = brightness,
            peakLuma = 200,
            localBackgroundLuma = 20.0,
            pixelCount = 12,
            saturated = false,
            nearEdge = false,
        )

    @Test
    fun `reports a full detection rate and low residual when every prediction is recovered`() {
        val predicted =
            listOf(
                PredictedPointPx(catalogIndex = 1, xPx = 100.0, yPx = 100.0),
                PredictedPointPx(catalogIndex = 2, xPx = 200.0, yPx = 150.0),
                PredictedPointPx(catalogIndex = 3, xPx = 300.0, yPx = 400.0),
            )
        val detections =
            listOf(
                source(100.1, 99.9),
                source(199.95, 150.05),
                source(300.2, 400.1),
            )

        val report = evaluateDetections(detections, predicted, tolerancePx = 3.0)

        assertEquals(3, report.matchedCount)
        assertEquals(1.0, assertNotNull(report.detectionRate))
        assertEquals(0, report.falsePositiveCount)
        assertTrue(assertNotNull(report.centroidResidualRmsPx) < 0.25)
        assertEquals(listOf(0, 1, 2), report.matches.map { it.predictedIndex })
    }

    @Test
    fun `counts an unpaired detection as a false positive and an unpaired prediction as a miss`() {
        val predicted =
            listOf(
                PredictedPointPx(catalogIndex = 1, xPx = 100.0, yPx = 100.0),
                PredictedPointPx(catalogIndex = 2, xPx = 200.0, yPx = 150.0),
            )
        val detections = listOf(source(100.2, 100.1), source(500.0, 480.0))

        val report = evaluateDetections(detections, predicted, tolerancePx = 3.0)

        assertEquals(1, report.matchedCount)
        assertEquals(0.5, assertNotNull(report.detectionRate))
        assertEquals(1, report.falsePositiveCount)
    }

    @Test
    fun `never lets two predictions claim the same detection`() {
        // Two predictions a pixel apart with one detection between them: the closer one wins and the
        // other is a miss, because a one-to-one assignment is the whole point of the greedy walk.
        val predicted =
            listOf(
                PredictedPointPx(catalogIndex = 1, xPx = 100.0, yPx = 100.0),
                PredictedPointPx(catalogIndex = 2, xPx = 101.0, yPx = 100.0),
            )
        val detections = listOf(source(100.2, 100.0))

        val report = evaluateDetections(detections, predicted, tolerancePx = 3.0)

        assertEquals(1, report.matchedCount)
        assertEquals(0, report.matches.single().predictedIndex, "the nearer prediction claims the detection")
        assertEquals(0, report.falsePositiveCount)
    }

    @Test
    fun `does not pair beyond the tolerance`() {
        val predicted = listOf(PredictedPointPx(catalogIndex = 1, xPx = 100.0, yPx = 100.0))
        val detections = listOf(source(105.0, 100.0))

        val report = evaluateDetections(detections, predicted, tolerancePx = 3.0)

        assertEquals(0, report.matchedCount)
        assertEquals(1, report.falsePositiveCount)
        assertNull(report.centroidResidualRmsPx, "an empty match set has no RMS, not an RMS of zero")
    }

    @Test
    fun `reports a null detection rate when there is nothing to recover`() {
        val report = evaluateDetections(listOf(source(10.0, 10.0)), predicted = emptyList(), tolerancePx = 3.0)

        assertNull(report.detectionRate, "a frame with no predictions cannot be scored, and is not a zero rate")
        assertEquals(1, report.falsePositiveCount)
    }

    @Test
    fun `drops predicted stars that have no image position`() {
        val stars =
            listOf(
                skyStar(catalogIndex = 1, imageXPx = 10.0, imageYPx = 20.0),
                // Behind the camera: a normal outcome, not a star the detector failed to find.
                skyStar(catalogIndex = 2, imageXPx = null, imageYPx = null),
                skyStar(catalogIndex = 3, imageXPx = 30.0, imageYPx = 40.0),
            )

        val points = stars.toPredictedPointsPx()

        assertEquals(listOf(1, 3), points.map { it.catalogIndex })
    }

    @Test
    fun `is deterministic when two pairings are exactly equidistant`() {
        val predicted =
            listOf(
                PredictedPointPx(catalogIndex = 1, xPx = 100.0, yPx = 100.0),
                PredictedPointPx(catalogIndex = 2, xPx = 102.0, yPx = 100.0),
            )
        val detections = listOf(source(101.0, 100.0), source(101.0, 100.0))

        val first = evaluateDetections(detections, predicted, tolerancePx = 3.0)
        val second = evaluateDetections(detections, predicted, tolerancePx = 3.0)

        assertEquals(first, second)
        assertEquals(2, first.matchedCount)
    }

    private fun skyStar(
        catalogIndex: Int,
        imageXPx: Double?,
        imageYPx: Double?,
    ): SkyPredictedStar =
        SkyPredictedStar(
            catalogIndex = catalogIndex,
            rightAscensionRad = 0.1,
            declinationRad = 0.2,
            classification =
                if (imageXPx == null) {
                    PredictedStarClassification.BEHIND_CAMERA
                } else {
                    PredictedStarClassification.VISIBLE_IN_VIEWPORT
                },
            imageXPx = imageXPx,
            imageYPx = imageYPx,
        )
}
