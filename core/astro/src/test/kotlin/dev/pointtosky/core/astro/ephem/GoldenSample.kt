package dev.pointtosky.core.astro.ephem

import dev.pointtosky.core.astro.coord.Equatorial
import java.time.Instant

data class GoldenSample(
    val body: Body,
    val instant: Instant,
    val expected: Equatorial,
    val expectedDistanceAu: Double?,
    val expectedPhase: Double?,
)
