package dev.pointtosky.core.astro.ephem

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.units.degToRad
import dev.pointtosky.core.astro.units.radToDeg
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import java.time.Instant

class SimpleEphemerisComputerTest {

    private val computer = SimpleEphemerisComputer()

    @Test
    fun `ephemerides stay within snapshot tolerance`() {
        val expectations = listOf(
            Expectation(
                body = Body.SUN,
                instant = Instant.parse("2025-01-01T00:00:00Z"),
                reference = Equatorial(281.3816159480803, -23.023818073708256),
                distanceAu = 0.9833531533438115,
                distanceToleranceAu = 0.02,
            ),
            Expectation(
                body = Body.MOON,
                instant = Instant.parse("2025-01-01T00:00:00Z"),
                reference = Equatorial(296.2989546824331, -25.9204566071867),
                distanceAu = 0.0025518035774020897,
                distanceToleranceAu = 0.0004,
            ),
            Expectation(
                body = Body.JUPITER,
                instant = Instant.parse("2025-01-01T00:00:00Z"),
                reference = Equatorial(71.50940460653568, 21.741416618004354),
                distanceAu = 4.190749912839749,
                distanceToleranceAu = 0.2,
            ),
            Expectation(
                body = Body.SATURN,
                instant = Instant.parse("2025-01-01T00:00:00Z"),
                reference = Equatorial(346.1911014538766, -8.05146645994369),
                distanceAu = 10.025328436790733,
                distanceToleranceAu = 0.3,
            ),
            Expectation(
                body = Body.SUN,
                instant = Instant.parse("2025-06-01T00:00:00Z"),
                reference = Equatorial(68.80564636440339, 22.006648932678075),
                distanceAu = 1.0139673291310598,
                distanceToleranceAu = 0.02,
            ),
            Expectation(
                body = Body.MOON,
                instant = Instant.parse("2025-06-01T00:00:00Z"),
                reference = Equatorial(138.579535833587, 19.299590609285758),
                distanceAu = 0.002565808135291837,
                distanceToleranceAu = 0.0004,
            ),
            Expectation(
                body = Body.JUPITER,
                instant = Instant.parse("2025-06-01T00:00:00Z"),
                reference = Equatorial(87.43588963964672, 23.236182742783996),
                distanceAu = 6.094027326980883,
                distanceToleranceAu = 0.3,
            ),
            Expectation(
                body = Body.SATURN,
                instant = Instant.parse("2025-06-01T00:00:00Z"),
                reference = Equatorial(0.958012106242118, -1.8813509195325069),
                distanceAu = 9.878564617518304,
                distanceToleranceAu = 0.3,
            ),
        )

        expectations.forEach { expected ->
            val ephemeris = computer.compute(expected.body, expected.instant)
            val separationDeg = angularSeparationDeg(ephemeris.eq, expected.reference)
            assertTrue(
                separationDeg <= 1.5,
                "${expected.body} @ ${expected.instant} deviates by $separationDeg°",
            )
            expected.distanceAu?.let { expectedDistance ->
                val actual = ephemeris.distanceAu
                assertNotNull(actual, "${expected.body} distance should be reported")
                val delta = abs(actual - expectedDistance)
                assertTrue(
                    delta <= expected.distanceToleranceAu,
                    "${expected.body} distance differs by $delta AU",
                )
            }
        }
    }

    @Test
    fun `sun declination stays near ecliptic at march equinox`() {
        val instant = Instant.parse("2025-03-20T00:00:00Z")
        val ephemeris = computer.compute(Body.SUN, instant)
        assertTrue(abs(ephemeris.eq.decDeg) < 2.5, "Sun declination expected near 0° at equinox")
    }

    @Test
    fun `moon phase stays normalized and varies across lunation`() {
        val instants = listOf(
            Instant.parse("2025-01-01T00:00:00Z"),
            Instant.parse("2025-01-07T00:00:00Z"),
            Instant.parse("2025-01-14T00:00:00Z"),
            Instant.parse("2025-01-21T00:00:00Z"),
            Instant.parse("2025-01-28T00:00:00Z"),
        )

        var minPhase = Double.POSITIVE_INFINITY
        var maxPhase = Double.NEGATIVE_INFINITY

        instants.forEach { instant ->
            val phase = computer.compute(Body.MOON, instant).phase
            assertNotNull(phase, "Moon phase must be reported")
            assertTrue(phase in 0.0..1.0, "Moon phase should be normalized, was $phase")
            minPhase = min(minPhase, phase)
            maxPhase = max(maxPhase, phase)
        }

        assertTrue(maxPhase - minPhase >= 0.25, "Moon phase should vary across sampled instants")
    }

    private data class Expectation(
        val body: Body,
        val instant: Instant,
        val reference: Equatorial,
        val distanceAu: Double?,
        val distanceToleranceAu: Double,
    )

    private fun angularSeparationDeg(a: Equatorial, b: Equatorial): Double {
        val ra1 = degToRad(a.raDeg)
        val dec1 = degToRad(a.decDeg)
        val ra2 = degToRad(b.raDeg)
        val dec2 = degToRad(b.decDeg)
        val cosDelta = sin(dec1) * sin(dec2) + cos(dec1) * cos(dec2) * cos(ra1 - ra2)
        return radToDeg(acos(cosDelta.coerceIn(-1.0, 1.0)))
    }
}
