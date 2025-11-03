package dev.pointtosky.core.astro.ephem

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.testkit.Angles.angularSeparationDeg
import dev.pointtosky.core.astro.testkit.Tolerance.EPHEM_MAX_ERR_DEG
import java.time.Instant
import kotlin.math.abs
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class SimpleEphemerisComputerGoldenTest {

    private val computer = SimpleEphemerisComputer()

    @ParameterizedTest(name = "{0} @ {1}")
    @MethodSource("goldenSamples")
    fun `ephemerides stay within angular tolerance`(sample: GoldenSample) {
        val ephemeris = computer.compute(sample.body, sample.instant)
        val separation = angularSeparationDeg(
            ephemeris.eq.raDeg,
            ephemeris.eq.decDeg,
            sample.expected.raDeg,
            sample.expected.decDeg,
        )

        assertTrue(
            separation <= EPHEM_MAX_ERR_DEG,
            "${sample.body} @ ${sample.instant} deviates by $separation°",
        )

        val expectedDistance = sample.expectedDistanceAu
        val expectedTolerance = sample.distanceToleranceAu
        if (expectedDistance != null && expectedTolerance != null) {
            val actual = ephemeris.distanceAu
            assertNotNull(actual, "${sample.body} distance should be reported")
            val delta = abs(actual - expectedDistance)
            assertTrue(
                delta <= expectedTolerance,
                "${sample.body} distance differs by $delta AU",
            )
        }

        if (sample.body == Body.MOON) {
            val phase = ephemeris.phase
            assertNotNull(phase, "Moon phase must be reported")
            assertTrue(phase in 0.0..1.0, "Moon phase should be normalized, was $phase")
        }
    }

    @Test
    fun `sun declination stays near zero at march equinox`() {
        val instant = Instant.parse("2025-03-20T00:00:00Z")
        val ephemeris = computer.compute(Body.SUN, instant)
        assertTrue(abs(ephemeris.eq.decDeg) < 2.5, "Sun declination expected near 0° at equinox")
    }

    @Test
    fun `moon phase remains normalized across day samples`() {
        val instants = listOf(
            Instant.parse("2025-01-01T00:00:00Z"),
            Instant.parse("2025-06-01T00:00:00Z"),
        )
        instants.forEach { instant ->
            val phase = computer.compute(Body.MOON, instant).phase
            assertNotNull(phase, "Moon phase must be reported")
            assertTrue(phase in 0.0..1.0, "Moon phase should be normalized, was $phase")
        }
    }

    companion object {
        private val GOLDEN_SAMPLES = listOf(
            GoldenSample(
                body = Body.SUN,
                instant = Instant.parse("2025-01-01T00:00:00Z"),
                expected = Equatorial(281.3816159480803, -23.023818073708256),
                expectedDistanceAu = 0.9833531533438115,
                distanceToleranceAu = 0.02,
            ),
            GoldenSample(
                body = Body.MOON,
                instant = Instant.parse("2025-01-01T00:00:00Z"),
                expected = Equatorial(296.2989546824331, -25.9204566071867),
                expectedDistanceAu = 0.0025518035774020897,
                distanceToleranceAu = 0.0004,
            ),
            GoldenSample(
                body = Body.JUPITER,
                instant = Instant.parse("2025-01-01T00:00:00Z"),
                expected = Equatorial(71.50940460653568, 21.741416618004354),
                expectedDistanceAu = 4.190749912839749,
                distanceToleranceAu = 0.2,
            ),
            GoldenSample(
                body = Body.SATURN,
                instant = Instant.parse("2025-01-01T00:00:00Z"),
                expected = Equatorial(346.1911014538766, -8.05146645994369),
                expectedDistanceAu = 10.025328436790733,
                distanceToleranceAu = 0.3,
            ),
            GoldenSample(
                body = Body.SUN,
                instant = Instant.parse("2025-06-01T00:00:00Z"),
                expected = Equatorial(68.80564636440339, 22.006648932678075),
                expectedDistanceAu = 1.0139673291310598,
                distanceToleranceAu = 0.02,
            ),
            GoldenSample(
                body = Body.MOON,
                instant = Instant.parse("2025-06-01T00:00:00Z"),
                expected = Equatorial(138.579535833587, 19.299590609285758),
                expectedDistanceAu = 0.002565808135291837,
                distanceToleranceAu = 0.0004,
            ),
            GoldenSample(
                body = Body.JUPITER,
                instant = Instant.parse("2025-06-01T00:00:00Z"),
                expected = Equatorial(87.43588963964672, 23.236182742783996),
                expectedDistanceAu = 6.094027326980883,
                distanceToleranceAu = 0.3,
            ),
            GoldenSample(
                body = Body.SATURN,
                instant = Instant.parse("2025-06-01T00:00:00Z"),
                expected = Equatorial(0.958012106242118, -1.8813509195325069),
                expectedDistanceAu = 9.878564617518304,
                distanceToleranceAu = 0.3,
            ),
        )

        // TODO(S4.D-T2): Replace stubbed golden values with authoritative ephemerides.

        @JvmStatic
        fun goldenSamples(): java.util.stream.Stream<GoldenSample> = GOLDEN_SAMPLES.stream()
    }

    data class GoldenSample(
        val body: Body,
        val instant: Instant,
        val expected: Equatorial,
        val expectedDistanceAu: Double?,
        val distanceToleranceAu: Double?,
    )
}
