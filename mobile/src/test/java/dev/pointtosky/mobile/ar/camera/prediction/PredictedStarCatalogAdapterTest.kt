package dev.pointtosky.mobile.ar.camera.prediction

import dev.pointtosky.core.astro.catalog.ConstellationId
import dev.pointtosky.core.astro.catalog.StarId
import dev.pointtosky.core.astro.catalog.StarRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * JVM tests for the CAM-2b bounded diagnostic input adapter (task §2, §12.G): stable catalog index,
 * exactly-once degree-to-radian conversion, magnitude pass-through, and deterministic bounded
 * selection.
 */
class PredictedStarCatalogAdapterTest {
    private fun star(
        rawId: Int,
        rightAscensionDeg: Float,
        declinationDeg: Float,
        magnitude: Float,
    ) = StarRecord(
        id = StarId(rawId),
        rightAscensionDeg = rightAscensionDeg,
        declinationDeg = declinationDeg,
        magnitude = magnitude,
        constellationId = ConstellationId(0),
        flags = 0,
        name = null,
    )

    @Test
    fun `catalogIndex is the star's stable raw id, not a positional index`() {
        val stars = listOf(star(rawId = 42_017, rightAscensionDeg = 10f, declinationDeg = 5f, magnitude = 1.0f))

        val result = selectPredictedStarDirections(stars, maxCount = 10)

        assertEquals(42_017, result.single().catalogIndex)
    }

    @Test
    fun `RA and Dec are converted from degrees to radians exactly once`() {
        val stars = listOf(star(rawId = 1, rightAscensionDeg = 180f, declinationDeg = 45f, magnitude = 1.0f))

        val result = selectPredictedStarDirections(stars, maxCount = 10)

        assertEquals(Math.toRadians(180.0), result.single().rightAscensionRad, 1e-12)
        assertEquals(Math.toRadians(45.0), result.single().declinationRad, 1e-12)
    }

    @Test
    fun `magnitude is passed through when present`() {
        val stars = listOf(star(rawId = 1, rightAscensionDeg = 0f, declinationDeg = 0f, magnitude = 2.5f))

        val result = selectPredictedStarDirections(stars, maxCount = 10)

        assertEquals(2.5, result.single().magnitude)
    }

    @Test
    fun `an input larger than the configured maximum produces exactly the bounded deterministic subset`() {
        val stars = (0 until 500).map { i -> star(rawId = i, rightAscensionDeg = 0f, declinationDeg = 0f, magnitude = i.toFloat()) }

        val result = selectPredictedStarDirections(stars, maxCount = 200)

        assertEquals(200, result.size)
        // Brightest-first (ascending magnitude): the 200 dimmest raw ids (0..199) survive.
        assertEquals((0 until 200).toList(), result.map { it.catalogIndex })
    }

    @Test
    fun `selection is deterministic - repeated calls on the same input produce the same output`() {
        val stars = (0 until 50).map { i -> star(rawId = i, rightAscensionDeg = 0f, declinationDeg = 0f, magnitude = (50 - i).toFloat()) }

        val first = selectPredictedStarDirections(stars, maxCount = 20)
        val second = selectPredictedStarDirections(stars, maxCount = 20)

        assertEquals(first, second)
    }

    @Test
    fun `selection sorts brightest-first before bounding, never leaving the bound to input order`() {
        val stars =
            listOf(
                star(rawId = 1, rightAscensionDeg = 0f, declinationDeg = 0f, magnitude = 5.0f),
                star(rawId = 2, rightAscensionDeg = 0f, declinationDeg = 0f, magnitude = 1.0f),
                star(rawId = 3, rightAscensionDeg = 0f, declinationDeg = 0f, magnitude = 3.0f),
            )

        val result = selectPredictedStarDirections(stars, maxCount = 2)

        assertEquals(listOf(2, 3), result.map { it.catalogIndex })
    }

    @Test
    fun `an empty input produces an empty output`() {
        assertTrue(selectPredictedStarDirections(emptyList(), maxCount = 200).isEmpty())
    }

    @Test
    fun `maxCount zero produces an empty output regardless of input size`() {
        val stars = listOf(star(rawId = 1, rightAscensionDeg = 0f, declinationDeg = 0f, magnitude = 1.0f))

        assertTrue(selectPredictedStarDirections(stars, maxCount = 0).isEmpty())
    }

    @Test
    fun `an input smaller than the maximum is not padded - every entry is returned`() {
        val stars = listOf(star(rawId = 1, rightAscensionDeg = 0f, declinationDeg = 0f, magnitude = 1.0f))

        assertEquals(1, selectPredictedStarDirections(stars, maxCount = 200).size)
    }

    @Test
    fun `the default bound is PREDICTED_STAR_OVERLAY_MAX_INPUT_STARS`() {
        val stars = (0 until 300).map { i -> star(rawId = i, rightAscensionDeg = 0f, declinationDeg = 0f, magnitude = i.toFloat()) }

        assertEquals(PREDICTED_STAR_OVERLAY_MAX_INPUT_STARS, selectPredictedStarDirections(stars).size)
    }
}
