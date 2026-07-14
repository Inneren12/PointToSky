package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.projection.camera.PixelPoint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/**
 * Pins [StarPredictionBatchResult.Ready]'s defensive-copy ownership guarantee: the class is
 * documented as an immutable, bounded batch result, and that must be actually true, not just true by
 * caller convention. The primary constructor is `private` (`@ConsistentCopyVisibility`), so [Ready.of]
 * is the only public construction path, and it stores a copy of whatever list it is given.
 */
class StarPredictionBatchResultTest {
    private fun behindCameraProjection(catalogIndex: Int) =
        PredictedStarProjection(
            catalogIndex = catalogIndex,
            magnitude = null,
            classification = PredictedStarClassification.BEHIND_CAMERA,
            cameraDirection = null,
            imagePoint = null,
            displayPoint = null,
        )

    private fun visibleProjection(catalogIndex: Int) =
        PredictedStarProjection(
            catalogIndex = catalogIndex,
            magnitude = null,
            classification = PredictedStarClassification.VISIBLE_IN_VIEWPORT,
            cameraDirection = CameraDirectionSnapshot(0.0, 0.0, 1.0, 0.0, 0.0),
            imagePoint = PixelPoint(500.0, 250.0),
            displayPoint = PixelPoint(500.0, 250.0),
        )

    @Test
    fun `of stores a defensive copy - mutating the source list afterward does not change Ready`() {
        val source = mutableListOf(behindCameraProjection(0))
        val ready = StarPredictionBatchResult.Ready.of(source)

        source.clear()

        assertEquals(1, ready.projections.size)
    }

    @Test
    fun `of stores a defensive copy - appending to the source list afterward does not change Ready`() {
        val source = mutableListOf(behindCameraProjection(0))
        val ready = StarPredictionBatchResult.Ready.of(source)

        source.add(visibleProjection(1))

        assertEquals(1, ready.projections.size)
        assertEquals(0, ready.projections.single().catalogIndex)
    }

    @Test
    fun `of never returns the exact same list instance it was given`() {
        val source = listOf(behindCameraProjection(0))
        val ready = StarPredictionBatchResult.Ready.of(source)

        assertNotSame(source, ready.projections)
    }

    @Test
    fun `of preserves input order`() {
        val source = listOf(visibleProjection(3), behindCameraProjection(1), visibleProjection(2))
        val ready = StarPredictionBatchResult.Ready.of(source)

        assertEquals(listOf(3, 1, 2), ready.projections.map { it.catalogIndex })
    }

    @Test
    fun `two Ready instances built from equal content lists are equal`() {
        val a = StarPredictionBatchResult.Ready.of(listOf(behindCameraProjection(0)))
        val b = StarPredictionBatchResult.Ready.of(listOf(behindCameraProjection(0)))
        assertEquals(a, b)
    }

    @Test
    fun `an empty list is accepted and produces an empty Ready`() {
        val ready = StarPredictionBatchResult.Ready.of(emptyList())
        assertEquals(emptyList(), ready.projections)
    }
}
