package dev.pointtosky.core.astro.ephem

import dev.pointtosky.core.astro.time.instantToJulianDay
import dev.pointtosky.core.astro.units.degToRad
import dev.pointtosky.core.astro.units.radToDeg
import java.time.Instant
import kotlin.math.atan
import kotlin.math.sin
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the two time arguments of [SimpleEphemerisComputer]:
 * - Schlyter day number `d`, zero at 2000 Jan 0.0 = 1999-12-31T00:00 = JD 2451543.5 (orbital elements);
 * - Julian centuries from J2000.0 = JD 2451545.0 (mean-obliquity polynomial).
 */
class EphemerisTimeArgumentsTest {
    @Test
    fun `Schlyter day zero is 1999-12-31T00Z`() {
        assertEquals(2451543.5, SCHLYTER_DAY_ZERO_JD, 0.0)
        assertEquals(0.0, schlyterDayAt("1999-12-31T00:00:00Z"), 0.0)
    }

    /** Schlyter's worked example: "19 april 1990, at 0:00 UT ... JD = 2448000.5 which yields d = -3543.0". */
    @Test
    fun `Schlyter worked example date gives d = -3543_0`() {
        assertEquals(-3543.0, schlyterDayAt("1990-04-19T00:00:00Z"), 0.0)
    }

    @Test
    fun `J2000 centuries and Schlyter day number use different origins`() {
        val j2000 = instantToJulianDay(Instant.parse("2000-01-01T12:00:00Z"))
        assertEquals(2451545.0, J2000_JD, 0.0)
        assertEquals(0.0, julianCenturiesFromJ2000(j2000), 0.0)
        assertEquals(1.5, schlyterDayNumber(j2000), 0.0)
    }

    /**
     * Black-box guard that compute() feeds the obliquity polynomial with Julian centuries from J2000.0 and not
     * the Schlyter day number. The model Sun has ecliptic latitude 0, so tan(Dec) = tan(ε) sin(RA) recovers
     * the obliquity ε actually used. Feeding `d / 36525` instead of `T` shifts ε by 0.0130042° × 1.5 / 36525
     * = 5.34e-7°, far outside the 1e-9° tolerance.
     */
    @Test
    fun `obliquity is evaluated at J2000 centuries, not at the Schlyter day number`() {
        // T = 0 at J2000.0: ε = 23.439291° (the polynomial's constant term, IAU 1976 84381.448").
        assertEquals(23.439291, sunObliquityDeg("2000-01-01T12:00:00Z"), 1e-9)
        // T = -1.5 / 36525 at Schlyter day zero: ε = 23.439291° + 0.0130042° × 1.5 / 36525.
        assertEquals(23.439291 + 5.340533880903491e-7, sunObliquityDeg("1999-12-31T00:00:00Z"), 1e-9)
    }

    private fun schlyterDayAt(utc: String): Double = schlyterDayNumber(instantToJulianDay(Instant.parse(utc)))

    private fun sunObliquityDeg(utc: String): Double {
        val sun = SimpleEphemerisComputer().compute(Body.SUN, Instant.parse(utc)).eq
        return radToDeg(atan(tan(degToRad(sun.decDeg)) / sin(degToRad(sun.raDeg))))
    }
}
