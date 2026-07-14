package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.projection.camera.PixelPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class StarPredictionSummaryTest {
    private val someCameraDirection = CameraDirectionSnapshot(0.0, 0.0, 1.0, 0.0, 0.0)
    private val someImagePoint = PixelPoint(500.0, 250.0)
    private val someDisplayPoint = PixelPoint(500.0, 250.0)

    // PredictedStarProjection's own init now requires cameraDirection/imagePoint/displayPoint to be
    // all-null for BEHIND_CAMERA and all-non-null for every other classification (see
    // PredictedStarProjectionTest) - this fixture supplies the right shape for whichever
    // classification is requested so this file's tests exercise StarPredictionSummary specifically.
    private fun projection(classification: PredictedStarClassification) =
        PredictedStarProjection(
            catalogIndex = 0,
            magnitude = null,
            classification = classification,
            cameraDirection = if (classification == PredictedStarClassification.BEHIND_CAMERA) null else someCameraDirection,
            imagePoint = if (classification == PredictedStarClassification.BEHIND_CAMERA) null else someImagePoint,
            displayPoint = if (classification == PredictedStarClassification.BEHIND_CAMERA) null else someDisplayPoint,
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

    // --- Non-negative counts -------------------------------------------------------------------------

    @Test
    fun `a negative inputCount is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StarPredictionSummary(
                inputCount = -1,
                behindCameraCount = 0,
                outsideImageCount = 0,
                insideImageOutsideViewportCount = 0,
                visibleInViewportCount = 0,
            )
        }
    }

    @Test
    fun `a negative behindCameraCount is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StarPredictionSummary(
                inputCount = -1,
                behindCameraCount = -1,
                outsideImageCount = 0,
                insideImageOutsideViewportCount = 0,
                visibleInViewportCount = 0,
            )
        }
    }

    @Test
    fun `a negative outsideImageCount is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StarPredictionSummary(
                inputCount = -1,
                behindCameraCount = 0,
                outsideImageCount = -1,
                insideImageOutsideViewportCount = 0,
                visibleInViewportCount = 0,
            )
        }
    }

    @Test
    fun `a negative insideImageOutsideViewportCount is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StarPredictionSummary(
                inputCount = -1,
                behindCameraCount = 0,
                outsideImageCount = 0,
                insideImageOutsideViewportCount = -1,
                visibleInViewportCount = 0,
            )
        }
    }

    @Test
    fun `a negative visibleInViewportCount is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            StarPredictionSummary(
                inputCount = -1,
                behindCameraCount = 0,
                outsideImageCount = 0,
                insideImageOutsideViewportCount = 0,
                visibleInViewportCount = -1,
            )
        }
    }

    // --- Overflow safety: four Int-range counts near Int.MAX_VALUE must not wrap into a false match ---

    @Test
    fun `four counts near Int MAX_VALUE that would overflow an Int sum are still rejected, not falsely accepted`() {
        // Each individual count is a valid, non-negative Int (so the per-field require() calls above
        // don't reject it on their own), but 4 * (Int.MAX_VALUE / 2) overflows Int arithmetic and wraps
        // to a small/negative number - if the sum were computed as Int, this could wrap around to
        // coincidentally equal a small, wrong inputCount and be falsely accepted. Computing the sum as
        // Long (see StarPredictionSummary's init) means it never overflows here, so this mismatched
        // total is correctly rejected instead.
        val quarterMax = Int.MAX_VALUE / 2
        assertFailsWith<IllegalArgumentException> {
            StarPredictionSummary(
                inputCount = 4,
                behindCameraCount = quarterMax,
                outsideImageCount = quarterMax,
                insideImageOutsideViewportCount = quarterMax,
                visibleInViewportCount = quarterMax,
            )
        }
    }

    @Test
    fun `a large but exactly matching inputCount near Int MAX_VALUE is accepted without overflow`() {
        val quarterMax = Int.MAX_VALUE / 4
        val summary =
            StarPredictionSummary(
                inputCount = quarterMax * 4,
                behindCameraCount = quarterMax,
                outsideImageCount = quarterMax,
                insideImageOutsideViewportCount = quarterMax,
                visibleInViewportCount = quarterMax,
            )
        assertEquals(quarterMax * 4, summary.inputCount)
    }
}
