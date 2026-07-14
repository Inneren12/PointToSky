package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.projection.camera.PixelPoint
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Pins the "no impossible public result state" invariant: [PredictedStarProjection.BEHIND_CAMERA]
 * must never carry camera-direction or pixel data, and every other classification must always carry
 * all three. See `StarPredictionSummaryTest`'s `projection()` fixture for a construction path that
 * satisfies this on purpose.
 */
class PredictedStarProjectionTest {
    private val someCameraDirection = CameraDirectionSnapshot(0.0, 0.0, 1.0, 0.0, 0.0)
    private val someImagePoint = PixelPoint(500.0, 250.0)
    private val someDisplayPoint = PixelPoint(500.0, 250.0)

    // --- BEHIND_CAMERA must carry no camera direction or pixel data ---------------------------------

    @Test
    fun `BEHIND_CAMERA with all null fields is accepted`() {
        PredictedStarProjection(
            catalogIndex = 0,
            magnitude = null,
            classification = PredictedStarClassification.BEHIND_CAMERA,
            cameraDirection = null,
            imagePoint = null,
            displayPoint = null,
        )
    }

    @Test
    fun `BEHIND_CAMERA carrying a cameraDirection is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PredictedStarProjection(
                catalogIndex = 0,
                magnitude = null,
                classification = PredictedStarClassification.BEHIND_CAMERA,
                cameraDirection = someCameraDirection,
                imagePoint = null,
                displayPoint = null,
            )
        }
    }

    @Test
    fun `BEHIND_CAMERA carrying pixel data is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PredictedStarProjection(
                catalogIndex = 0,
                magnitude = null,
                classification = PredictedStarClassification.BEHIND_CAMERA,
                cameraDirection = null,
                imagePoint = someImagePoint,
                displayPoint = someDisplayPoint,
            )
        }
    }

    // --- Every other classification must carry all three --------------------------------------------

    @Test
    fun `OUTSIDE_IMAGE with all three fields present is accepted`() {
        PredictedStarProjection(
            catalogIndex = 0,
            magnitude = null,
            classification = PredictedStarClassification.OUTSIDE_IMAGE,
            cameraDirection = someCameraDirection,
            imagePoint = someImagePoint,
            displayPoint = someDisplayPoint,
        )
    }

    @Test
    fun `OUTSIDE_IMAGE missing all three fields is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PredictedStarProjection(
                catalogIndex = 0,
                magnitude = null,
                classification = PredictedStarClassification.OUTSIDE_IMAGE,
                cameraDirection = null,
                imagePoint = null,
                displayPoint = null,
            )
        }
    }

    @Test
    fun `INSIDE_IMAGE_OUTSIDE_VIEWPORT missing pixel data is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PredictedStarProjection(
                catalogIndex = 0,
                magnitude = null,
                classification = PredictedStarClassification.INSIDE_IMAGE_OUTSIDE_VIEWPORT,
                cameraDirection = someCameraDirection,
                imagePoint = null,
                displayPoint = null,
            )
        }
    }

    @Test
    fun `VISIBLE_IN_VIEWPORT missing cameraDirection is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PredictedStarProjection(
                catalogIndex = 0,
                magnitude = null,
                classification = PredictedStarClassification.VISIBLE_IN_VIEWPORT,
                cameraDirection = null,
                imagePoint = someImagePoint,
                displayPoint = someDisplayPoint,
            )
        }
    }

    @Test
    fun `a partial (only some fields null) combination is rejected regardless of classification`() {
        assertFailsWith<IllegalArgumentException> {
            PredictedStarProjection(
                catalogIndex = 0,
                magnitude = null,
                classification = PredictedStarClassification.VISIBLE_IN_VIEWPORT,
                cameraDirection = someCameraDirection,
                imagePoint = someImagePoint,
                displayPoint = null,
            )
        }
    }

    // --- Other scalar validation ----------------------------------------------------------------------

    @Test
    fun `a negative catalogIndex is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PredictedStarProjection(
                catalogIndex = -1,
                magnitude = null,
                classification = PredictedStarClassification.BEHIND_CAMERA,
                cameraDirection = null,
                imagePoint = null,
                displayPoint = null,
            )
        }
    }

    @Test
    fun `a non-finite magnitude is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PredictedStarProjection(
                catalogIndex = 0,
                magnitude = Double.NaN,
                classification = PredictedStarClassification.BEHIND_CAMERA,
                cameraDirection = null,
                imagePoint = null,
                displayPoint = null,
            )
        }
    }

    @Test
    fun `a finite magnitude is accepted`() {
        PredictedStarProjection(
            catalogIndex = 0,
            magnitude = 4.2,
            classification = PredictedStarClassification.BEHIND_CAMERA,
            cameraDirection = null,
            imagePoint = null,
            displayPoint = null,
        )
    }

    // --- CameraDirectionSnapshot: all scalars must be finite -----------------------------------------

    @Test
    fun `a non-finite CameraDirectionSnapshot component is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CameraDirectionSnapshot(
                cameraX = Double.NaN,
                cameraY = 0.0,
                cameraZ = 1.0,
                normalizedX = 0.0,
                normalizedY = 0.0,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CameraDirectionSnapshot(
                cameraX = 0.0,
                cameraY = 0.0,
                cameraZ = 1.0,
                normalizedX = Double.POSITIVE_INFINITY,
                normalizedY = 0.0,
            )
        }
    }
}
