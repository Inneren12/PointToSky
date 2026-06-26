package dev.pointtosky.core.astro.visibility

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.Ephemeris
import dev.pointtosky.core.astro.ephem.EphemerisComputer
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.transform.raDecToAltAz
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Deterministic wiring tests for [limitingMagnitudeAt]. A fake [EphemerisComputer] returns fixed
 * Moon/Sun positions, so no real ephemerides are needed — the geometry is fully under test control.
 */
class LimitingMagnitudeAtTest {

    /** Returns a fixed [Ephemeris] per body — deterministic, no real ephemeris maths. */
    private class FakeEphemeris(
        private val moon: Ephemeris,
        private val sun: Ephemeris,
    ) : EphemerisComputer {
        override fun compute(body: Body, instant: Instant): Ephemeris =
            when (body) {
                Body.MOON -> moon
                Body.SUN -> sun
                else -> error("unexpected body $body")
            }
    }

    private val instant: Instant = Instant.parse("2026-06-26T22:00:00Z")
    private val latDeg = 89.0
    private val lonDeg = 0.0
    private val darkNelm = Bortle.CLASS_4.darkNelm // 6.3

    @Test
    fun moonAndSunBelowHorizonGivesNoPenalty() {
        // At lat 89°, a body at dec −80° culminates at ≈ −79° → always below the horizon.
        val below = Ephemeris(eq = Equatorial(0.0, -80.0), phase = 1.0)
        val computer = FakeEphemeris(moon = below, sun = below)

        val result = limitingMagnitudeAt(computer, instant, latDeg, lonDeg, darkNelm)

        assertEquals(darkNelm, result, 1e-9)
    }

    @Test
    fun highFullMoonAppliesPenalty() {
        // Moon on the meridian at dec = lat → altitude ≈ 90°, fully illuminated → moon penalty.
        val lstDeg = lstAt(instant, lonDeg).lstDeg
        val moon = Ephemeris(eq = Equatorial(lstDeg, latDeg), phase = 1.0)
        val sun = Ephemeris(eq = Equatorial(0.0, -80.0), phase = 0.0) // below horizon → no twilight
        val computer = FakeEphemeris(moon = moon, sun = sun)

        val result = limitingMagnitudeAt(computer, instant, latDeg, lonDeg, darkNelm)

        assertTrue(result < darkNelm, "expected penalty: result $result should be < darkNelm $darkNelm")
    }

    @Test
    fun matchesDirectEstimateForFixedPositions() {
        val moon = Ephemeris(eq = Equatorial(123.0, 35.0), phase = 0.7)
        val sun = Ephemeris(eq = Equatorial(250.0, -12.0), phase = 0.0)
        val computer = FakeEphemeris(moon = moon, sun = sun)

        val lstDeg = lstAt(instant, lonDeg).lstDeg
        val moonAlt = raDecToAltAz(eq = moon.eq, lstDeg = lstDeg, latDeg = latDeg, applyRefraction = false).altDeg
        val sunAlt = raDecToAltAz(eq = sun.eq, lstDeg = lstDeg, latDeg = latDeg, applyRefraction = false).altDeg
        val expected = estimateLimitingMagnitude(
            darkNelm = darkNelm,
            moonAltitudeDeg = moonAlt,
            moonIllumination = moon.phase ?: 0.0,
            sunAltitudeDeg = sunAlt,
        )

        val result = limitingMagnitudeAt(computer, instant, latDeg, lonDeg, darkNelm)

        assertEquals(expected, result, 1e-12)
    }
}
