package dev.pointtosky.core.astro.projection.camera.prediction

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StarPredictionSummaryTest {
    private fun projection(classification: PredictedStarClassification) =
        PredictedStarProjection(
            catalogIndex = 0,
            magnitude = null,
            classification = classification,
            cameraDirection = null,
            imagePoint = null,
            displayPoint = null,
        )

    @Test
    fun `empty input summarizes to all-zero counts`() {
        val summary = summarizeStarPredictions(emptyList())
        assertEquals(0, summary.inputCount)
        assertEquals(0, summary.behindCameraCount)
        assertEquals(0, summary.outsideImageCount)
        assertEquals(0, summary.insideImageOutsideViewportCount)
        assertEquals(0, summary.visibleInViewportCount)
    }

    @Test
    fun `counts each classification exactly once`() {
        val projections =
            listOf(
                projection(PredictedStarClassification.BEHIND_CAMERA),
                projection(PredictedStarClassification.BEHIND_CAMERA),
                projection(PredictedStarClassification.OUTSIDE_IMAGE),
                projection(PredictedStarClassification.INSIDE_IMAGE_OUTSIDE_VIEWPORT),
                projection(PredictedStarClassification.INSIDE_IMAGE_OUTSIDE_VIEWPORT),
                projection(PredictedStarClassification.INSIDE_IMAGE_OUTSIDE_VIEWPORT),
                projection(PredictedStarClassification.VISIBLE_IN_VIEWPORT),
            )
        val summary = summarizeStarPredictions(projections)
        assertEquals(7, summary.inputCount)
        assertEquals(2, summary.behindCameraCount)
        assertEquals(1, summary.outsideImageCount)
        assertEquals(3, summary.insideImageOutsideViewportCount)
        assertEquals(1, summary.visibleInViewportCount)
    }

    @Test
    fun `counts always sum to inputCount`() {
        val projections = List(11) { i -> projection(PredictedStarClassification.entries[i % PredictedStarClassification.entries.size]) }
        val summary = summarizeStarPredictions(projections)
        val sum = summary.behindCameraCount + summary.outsideImageCount + summary.insideImageOutsideViewportCount + summary.visibleInViewportCount
        assertEquals(summary.inputCount, sum)
    }

    @Test
    fun `a mismatched count total is rejected by the constructor`() {
        assertFailsWith<IllegalArgumentException> {
            StarPredictionSummary(
                inputCount = 5,
                behindCameraCount = 1,
                outsideImageCount = 1,
                insideImageOutsideViewportCount = 1,
                visibleInViewportCount = 1,
            )
        }
    }

    @Test
    fun `a matching count total is accepted`() {
        StarPredictionSummary(
            inputCount = 4,
            behindCameraCount = 1,
            outsideImageCount = 1,
            insideImageOutsideViewportCount = 1,
            visibleInViewportCount = 1,
        )
    }
}
