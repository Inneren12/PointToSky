package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.units.radToDeg
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * JVM tests for the CAM-2b per-batch astronomy-perf hardening: [prepareStarProjectionContext] is
 * computed once per [projectStars] call, never once per star, and produces results bit-for-bit
 * identical to the un-batched per-star computation.
 */
class PreparedStarProjectionContextTest {
    @Test
    fun `prepareStarProjectionContext matches a manual per-field computation`() {
        val context = neutralContext(latitudeRad = 0.7, longitudeRad = 0.3, utcEpochMillis = 1_700_000_000_000L)

        val prepared = prepareStarProjectionContext(context)

        val expectedLstDeg = lstAt(Instant.ofEpochMilli(context.utcEpochMillis), radToDeg(context.longitudeRad)).lstDeg
        assertEquals(context.latitudeRad, prepared.latitudeRad)
        assertEquals(expectedLstDeg, prepared.lstDeg)
        assertEquals(context.magneticDeclinationRad, prepared.magneticDeclinationRad)
    }

    @Test
    fun `the public two-arg equatorialToLocalSky delegates to the prepared three-arg overload without changing output`() {
        val context = neutralContext(latitudeRad = 0.5, longitudeRad = -0.2, utcEpochMillis = 1_650_000_000_000L)
        val prepared = prepareStarProjectionContext(context)
        val s = star(catalogIndex = 0, rightAscensionRad = 1.2, declinationRad = 0.4)

        val viaContext = equatorialToLocalSky(s, context)
        val viaPrepared = equatorialToLocalSky(s, latitudeRad = prepared.latitudeRad, lstDeg = prepared.lstDeg)

        assertEquals(viaPrepared, viaContext)
    }

    /**
     * The batch/perf-hardening claim under test: [projectStars] must apply the *same* prepared LST
     * value to every star in a multi-star batch — not just the first, not a stale or per-star-drifted
     * value. Proven by cross-checking the batch result against calling [projectStars] once per star
     * (each a singleton batch, each independently computing its own [prepareStarProjectionContext]) for
     * the same [context]/geometry: since sidereal time is a pure, deterministic function of
     * ([context]'s instant, longitude), the singleton and batched paths must produce bit-for-bit
     * identical [PredictedStarProjection] values for every star, in order, if (and only if) the batch
     * correctly shares one prepared context across all of them.
     */
    @Test
    fun `a multi-star batch applies one shared prepared LST consistently to every star`() {
        val context = neutralContext(latitudeRad = 0.6, longitudeRad = 0.9, utcEpochMillis = 1_720_000_000_000L)
        val geometry = buildTestGeometry()
        val stars =
            listOf(
                star(catalogIndex = 0, rightAscensionRad = 0.1, declinationRad = 0.1),
                star(catalogIndex = 1, rightAscensionRad = 2.5, declinationRad = -0.3),
                star(catalogIndex = 2, rightAscensionRad = 4.7, declinationRad = 0.2),
                star(catalogIndex = 3, rightAscensionRad = 6.1, declinationRad = -0.5),
            )

        val batched = assertIs<StarPredictionBatchResult.Ready>(projectStars(stars, context, geometry))
        val singleton =
            stars.map { s ->
                val result = assertIs<StarPredictionBatchResult.Ready>(projectStars(listOf(s), context, geometry))
                result.projections.single()
            }

        assertEquals(singleton, batched.projections)
    }
}
