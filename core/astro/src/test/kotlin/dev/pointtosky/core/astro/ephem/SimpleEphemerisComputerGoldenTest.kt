package dev.pointtosky.core.astro.ephem

import dev.pointtosky.core.astro.testkit.Angles.angularSeparationDeg
import dev.pointtosky.core.astro.testkit.Tolerance.EPHEM_MAX_ERR_DEG
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.opentest4j.TestAbortedException
import java.time.Instant
import kotlin.math.abs

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SimpleEphemerisComputerGoldenTest {

    private val computer = SimpleEphemerisComputer()
    private val golden = GOLDEN_DATA

    @BeforeAll
    fun maybeUpdateGolden() {
        if (!REQUEST_UPDATE) {
            return
        }
        if (!CAN_WRITE_GOLDEN) {
            throw TestAbortedException(
                "Golden ephemerides update requested, but disabled when CI environment is detected.",
            )
        }

        val updated = golden.recompute(computer)
        writeGolden(updated)
        throw TestAbortedException("Golden ephemerides updated. Re-run without -PupdateGolden=true.")
    }

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
        if (expectedDistance != null) {
            val actual = ephemeris.distanceAu ?: fail("${sample.body} distance should be reported")
            val delta = abs(actual - expectedDistance)
            val expectedTolerance = DISTANCE_TOLERANCES.getValue(sample.body)
            assertTrue(
                delta <= expectedTolerance,
                "${sample.body} distance differs by $delta AU",
            )
        }

        val expectedPhase = sample.expectedPhase
        if (expectedPhase != null) {
            val phase = ephemeris.phase ?: fail("${sample.body} phase must be reported")
            val delta = abs(phase - expectedPhase)
            assertTrue(delta <= PHASE_TOLERANCE, "${sample.body} phase differs by $delta")
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
        golden.dates.map { it.instant }.forEach { instant ->
            val phase = computer.compute(Body.MOON, instant).phase
            assertNotNull(phase, "Moon phase must be reported")
            assertTrue(phase in 0.0..1.0, "Moon phase should be normalized, was $phase")
        }
    }

    companion object {
        private val GOLDEN_DATA = readGolden()
        private val REQUEST_UPDATE = System.getProperty("updateGolden")?.toBoolean() == true
        private val CAN_WRITE_GOLDEN = REQUEST_UPDATE && System.getenv("CI") == null
        private val DISTANCE_TOLERANCES = mapOf(
            Body.SUN to 0.02,
            Body.MOON to 0.0005,
            Body.JUPITER to 0.3,
            Body.SATURN to 0.3,
        )
        private const val PHASE_TOLERANCE = 5e-4

        @JvmStatic
        fun goldenSamples(): java.util.stream.Stream<GoldenSample> = GOLDEN_DATA.toSamples().stream()
    }
}
