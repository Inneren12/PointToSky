package dev.pointtosky.core.astro.ephem

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.units.degToRad
import java.time.Instant
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SimpleEphemerisComputerTest {

    private val computer = SimpleEphemerisComputer()
    private val referenceCases = listOf(
        ReferenceCase(Instant.parse("2025-01-01T00:00:00Z"), Body.SUN, 281.387875, -23.023361),
        ReferenceCase(Instant.parse("2025-06-01T00:00:00Z"), Body.SUN, 68.811625, 22.007444),
        ReferenceCase(Instant.parse("2025-01-01T00:00:00Z"), Body.MOON, 296.305125, -25.919361),
        ReferenceCase(Instant.parse("2025-06-01T00:00:00Z"), Body.MOON, 138.581833, 19.298611),
        ReferenceCase(Instant.parse("2025-01-01T00:00:00Z"), Body.JUPITER, 71.503917, 21.740806),
        ReferenceCase(Instant.parse("2025-06-01T00:00:00Z"), Body.JUPITER, 87.441708, 23.236278),
        ReferenceCase(Instant.parse("2025-01-01T00:00:00Z"), Body.SATURN, 346.193417, -8.050306),
        ReferenceCase(Instant.parse("2025-06-01T00:00:00Z"), Body.SATURN, 0.959875, -1.880750),
    )

    @Test
    fun `ephemerides stay within one and half degrees of reference`() {
        val toleranceDeg = 1.5
        referenceCases.forEach { case ->
            val ephemeris = computer.compute(case.body, case.instant)
            val distance = angularSeparationDeg(ephemeris.eq, case.raDeg, case.decDeg)
            assertTrue(
                distance <= toleranceDeg,
                "Angular distance for ${case.body} at ${case.instant} was ${"%.3f".format(distance)}°",
            )
            assertNotNull(ephemeris.distanceAu)
            if (case.body == Body.MOON) {
                assertNotNull(ephemeris.phase)
            }
        }
    }

    @Test
    fun `sun declination near equinox is close to zero`() {
        val instant = Instant.parse("2025-03-20T00:00:00Z")
        val ephemeris = computer.compute(Body.SUN, instant)
        assertTrue(abs(ephemeris.eq.decDeg) < 1.0)
    }

    @Test
    fun `moon phase stays within bounds and varies over time`() {
        val first = computer.compute(Body.MOON, Instant.parse("2025-01-01T00:00:00Z"))
        val second = computer.compute(Body.MOON, Instant.parse("2025-01-15T00:00:00Z"))
        val phase1 = first.phase
        val phase2 = second.phase
        assertNotNull(phase1)
        assertNotNull(phase2)
        assertTrue(phase1 in 0.0..1.0)
        assertTrue(phase2 in 0.0..1.0)
        assertTrue(abs(phase2 - phase1) > 0.05)
    }

    private fun angularSeparationDeg(eq: Equatorial, refRaDeg: Double, refDecDeg: Double): Double {
        val ra1 = degToRad(eq.raDeg)
        val dec1 = degToRad(eq.decDeg)
        val ra2 = degToRad(refRaDeg)
        val dec2 = degToRad(refDecDeg)
        val cosDistance = sin(dec1) * sin(dec2) + cos(dec1) * cos(dec2) * cos(ra1 - ra2)
        return acos(cosDistance.coerceIn(-1.0, 1.0)) * 180.0 / PI
    }

    private data class ReferenceCase(
        val instant: Instant,
        val body: Body,
        val raDeg: Double,
        val decDeg: Double,
    )
}
