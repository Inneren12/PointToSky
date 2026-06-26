package dev.pointtosky.core.astro.visibility

import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.EphemerisComputer
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.transform.raDecToAltAz
import java.time.Instant

/**
 * Compute the naked-eye limiting magnitude here & now: run the Sun/Moon ephemeris dance for
 * [instant] at [latDeg]/[lonDeg], then feed the resulting Moon/Sun altitudes (and Moon illumination)
 * into [estimateLimitingMagnitude] on top of the site [darkNelm].
 *
 * Pure given an [ephemeris] — callers pass the computer so this stays free of singletons. Shared by
 * the AR overlay, the sky map, and the object card so visibility is resolved identically everywhere.
 */
fun limitingMagnitudeAt(
    ephemeris: EphemerisComputer,
    instant: Instant,
    latDeg: Double,
    lonDeg: Double,
    darkNelm: Double,
): Double {
    val lstDeg = lstAt(instant, lonDeg).lstDeg
    val moon = ephemeris.compute(Body.MOON, instant)
    val sun = ephemeris.compute(Body.SUN, instant)
    val moonAlt = raDecToAltAz(eq = moon.eq, lstDeg = lstDeg, latDeg = latDeg, applyRefraction = false).altDeg
    val sunAlt = raDecToAltAz(eq = sun.eq, lstDeg = lstDeg, latDeg = latDeg, applyRefraction = false).altDeg
    return estimateLimitingMagnitude(
        darkNelm = darkNelm,
        moonAltitudeDeg = moonAlt,
        moonIllumination = moon.phase ?: 0.0,
        sunAltitudeDeg = sunAlt,
    )
}
