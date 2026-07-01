package dev.pointtosky.core.astro.visibility

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TOL = 1e-9

private fun assertNear(expected: Double, actual: Double, tol: Double = TOL) {
    assertTrue(abs(actual - expected) <= tol, "Expected ~$expected but was $actual (tol $tol)")
}

class SqmConversionsTest {

    // ── bortleFromSqm: anchors return exact half-integer Bortle values ────────

    @Test
    fun anchorsReturnExactFractionalBortle() {
        assertNear(1.5, bortleFromSqm(21.75))
        assertNear(2.5, bortleFromSqm(21.50))
        assertNear(3.5, bortleFromSqm(21.25))
        assertNear(4.5, bortleFromSqm(20.40))
        assertNear(5.5, bortleFromSqm(19.10))
        assertNear(6.5, bortleFromSqm(18.50))
        assertNear(7.5, bortleFromSqm(18.00))
        assertNear(8.5, bortleFromSqm(17.50))
    }

    @Test
    fun midpointOfBortle6BandInterpolatesToSix() {
        assertNear(6.0, bortleFromSqm(18.80))
    }

    @Test
    fun bortleFromSqmClampsToOneNine() {
        assertNear(1.0, bortleFromSqm(30.0))
        assertNear(9.0, bortleFromSqm(0.0))
    }

    @Test
    fun bortleFromSqmMonotonicallyDecreasesWithSqm() {
        val sqms = listOf(17.0, 17.5, 18.0, 18.5, 19.0, 19.5, 20.0, 20.5, 21.0, 21.5, 22.0)
        var prev = Double.NEGATIVE_INFINITY
        for (sqm in sqms) {
            val b = bortleFromSqm(sqm)
            assertTrue(b >= prev - 1e-12, "fractionalBortle decreased as sqm rose to $sqm")
            prev = b
        }
    }

    // ── darkNelmFromSqm: exact enum values at integer Bortle, clamp, monotone ─

    @Test
    fun darkNelmAtIntegerBortleMatchesEnum() {
        for (bortle in Bortle.entries) {
            assertNear(bortle.darkNelm, darkNelmFromSqm(bortle.representativeSqm))
        }
    }

    @Test
    fun representativeSqmMapsToExactIntegerBortle() {
        for ((index, bortle) in Bortle.entries.withIndex()) {
            assertNear((index + 1).toDouble(), bortleFromSqm(bortle.representativeSqm))
        }
    }

    @Test
    fun darkNelmFromSqmClampsToFourSevenEight() {
        assertNear(7.8, darkNelmFromSqm(30.0))
        assertNear(4.0, darkNelmFromSqm(0.0))
    }

    @Test
    fun darkNelmFromSqmMonotonicallyIncreasesWithSqm() {
        val sqms = listOf(17.0, 17.5, 18.0, 18.5, 19.0, 19.5, 20.0, 20.5, 21.0, 21.5, 22.0)
        var prev = Double.NEGATIVE_INFINITY
        for (sqm in sqms) {
            val n = darkNelmFromSqm(sqm)
            assertTrue(n >= prev - 1e-12, "darkNelm decreased as sqm rose to $sqm")
            prev = n
        }
    }

    // ── SkyBrightness value class exposes both derived properties ─────────────

    @Test
    fun skyBrightnessExposesFractionalBortleAndDarkNelm() {
        val sky = SkyBrightness(18.80)
        assertEquals(bortleFromSqm(18.80), sky.fractionalBortle)
        assertEquals(darkNelmFromSqm(18.80), sky.darkNelm)
    }
}
