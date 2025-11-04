package dev.pointtosky.core.astro.ephem

import dev.pointtosky.core.astro.coord.Equatorial
import java.time.Instant

/** Snapshot of expected ephemeris data for a celestial body at a moment in time. */
data class GoldenSample(
    val body: Body,
    val instant: Instant,
    val expected: Equatorial,
    val expectedDistanceAu: Double? = null,
    val expectedPhase: Double? = null,
)
