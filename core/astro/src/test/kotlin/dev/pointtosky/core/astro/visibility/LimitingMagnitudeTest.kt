package dev.pointtosky.core.astro.visibility

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

private const val TOL = 0.05

private fun assertNear(expected: Double, actual: Double, msg: String = "") {
    assertTrue(
        abs(actual - expected) <= TOL,
        "Expected ~$expected but was $actual (tol $TOL)${if (msg.isNotEmpty()) " — $msg" else ""}",
    )
}

class LimitingMagnitudeTest {

    // ── Fixed-value scenarios (Bortle 4 unless noted) ─────────────────────────

    @Test
    fun darkSkyNoMoonDeepNight() {
        assertNear(6.30, estimateLimitingMagnitude(6.3, -10.0, 0.0, -30.0))
    }

    @Test
    fun pristineBortle1() {
        assertNear(7.80, estimateLimitingMagnitude(7.8, -10.0, 0.0, -40.0))
    }

    @Test
    fun fullMoonAtZenith() {
        // penalty = 3.0 * 1.0 * sin(80°) ≈ 2.954 → limit ≈ 3.346
        assertNear(3.35, estimateLimitingMagnitude(6.3, 80.0, 1.0, -30.0))
    }

    @Test
    fun fullMoonAt20Degrees() {
        // penalty = 3.0 * 1.0 * sin(20°) ≈ 1.026 → limit ≈ 5.274
        assertNear(5.27, estimateLimitingMagnitude(6.3, 20.0, 1.0, -30.0))
    }

    @Test
    fun newMoonUpIllumZero() {
        assertNear(6.30, estimateLimitingMagnitude(6.3, 60.0, 0.0, -30.0))
    }

    @Test
    fun moonBelowHorizonIllumFull() {
        assertNear(6.30, estimateLimitingMagnitude(6.3, -5.0, 1.0, -30.0))
    }

    @Test
    fun nauticalTwilightSunMinus9() {
        // t = (-9 − (−18)) / 18 = 0.5 → penalty = 12 × 0.25 = 3.0 → limit = 3.30
        assertNear(3.30, estimateLimitingMagnitude(6.3, -10.0, 0.0, -9.0))
    }

    @Test
    fun astroTwilightEdgeSunMinus18() {
        assertNear(6.30, estimateLimitingMagnitude(6.3, -10.0, 0.0, -18.0))
    }

    @Test
    fun daytimeSunPlus5FloorApplied() {
        assertNear(-4.00, estimateLimitingMagnitude(6.3, -10.0, 0.0, 5.0))
    }

    @Test
    fun cityBortle8FullMoonZenith() {
        // 4.3 − 3.0 × sin(80°) ≈ 4.3 − 2.954 = 1.346
        assertNear(1.35, estimateLimitingMagnitude(4.3, 80.0, 1.0, -30.0))
    }

    // ── Property: result always in [FLOOR_NELM, darkNelm] ─────────────────────

    @Test
    fun resultNeverExceedsDarkNelm() {
        val darkNelm = 6.3
        val altitudes = listOf(-20.0, 0.0, 30.0, 60.0, 90.0)
        val sunAlts = listOf(-30.0, -18.0, -9.0, 0.0, 5.0)
        for (alt in altitudes) {
            for (sunAlt in sunAlts) {
                val result = estimateLimitingMagnitude(darkNelm, alt, 1.0, sunAlt)
                assertTrue(result <= darkNelm + 1e-9, "result $result exceeds darkNelm $darkNelm")
            }
        }
    }

    @Test
    fun resultNeverBelowFloor() {
        val darkNelm = 6.3
        val altitudes = listOf(-20.0, 0.0, 30.0, 60.0, 90.0)
        val sunAlts = listOf(-30.0, -18.0, -9.0, 0.0, 5.0)
        for (alt in altitudes) {
            for (sunAlt in sunAlts) {
                val result = estimateLimitingMagnitude(darkNelm, alt, 1.0, sunAlt)
                assertTrue(result >= -4.0 - 1e-9, "result $result below floor -4.0")
            }
        }
    }

    // ── Property: monotonically non-increasing as moonAltitude rises (illum 1) ─

    @Test
    fun limitNonIncreasingAsMoonRises() {
        val alts = listOf(-10.0, 0.0, 10.0, 20.0, 40.0, 60.0, 80.0, 90.0)
        var prev = Double.MAX_VALUE
        for (alt in alts) {
            val result = estimateLimitingMagnitude(6.3, alt, 1.0, -30.0)
            assertTrue(result <= prev + 1e-9, "limit increased from $prev to $result as alt rose to $alt")
            prev = result
        }
    }

    // ── Property: monotonically non-increasing as sunAltitude rises from -18 to 0 ─

    @Test
    fun limitNonIncreasingAsSunRises() {
        val sunAlts = listOf(-18.0, -15.0, -12.0, -9.0, -6.0, -3.0, 0.0)
        var prev = Double.MAX_VALUE
        for (sunAlt in sunAlts) {
            val result = estimateLimitingMagnitude(6.3, -10.0, 0.0, sunAlt)
            assertTrue(result <= prev + 1e-9, "limit increased from $prev to $result as sunAlt rose to $sunAlt")
            prev = result
        }
    }
}
