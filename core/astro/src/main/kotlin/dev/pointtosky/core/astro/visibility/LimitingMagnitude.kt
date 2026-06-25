package dev.pointtosky.core.astro.visibility

import kotlin.math.sin

/** Bortle dark-sky class → approximate naked-eye limiting magnitude (NELM) under a moonless,
 *  fully dark sky. Friendly labels live in the UI layer; this is the standard 1–9 scale. */
enum class Bortle(val darkNelm: Double) {
    CLASS_1(7.8),  // excellent dark-sky site
    CLASS_2(7.3),
    CLASS_3(6.8),  // rural
    CLASS_4(6.3),  // rural/suburban transition (default)
    CLASS_5(5.8),  // suburban
    CLASS_6(5.3),  // bright suburban
    CLASS_7(4.8),  // suburban/urban transition
    CLASS_8(4.3),  // city
    CLASS_9(4.0),  // inner city
}

/**
 * Coarse estimate of the naked-eye limiting magnitude here & now — the faintest star plausibly
 * visible. Starts from the site's dark-sky NELM and subtracts penalties for moonlight and twilight.
 * Approximate by design (no absolute photometry): a Bortle-scale bucket, not a precise value.
 * Constants below are first-order and intentionally tunable.
 *
 * @param darkNelm site dark-sky NELM (e.g. [Bortle.darkNelm])
 * @param moonAltitudeDeg Moon altitude; < 0 ⇒ below horizon ⇒ no moonlight penalty
 * @param moonIllumination illuminated fraction 0..1 (e.g. Ephemeris.phase)
 * @param sunAltitudeDeg Sun altitude; > 0 ⇒ daytime
 */
fun estimateLimitingMagnitude(
    darkNelm: Double,
    moonAltitudeDeg: Double,
    moonIllumination: Double,
    sunAltitudeDeg: Double,
): Double {
    val limit = darkNelm - moonPenalty(moonAltitudeDeg, moonIllumination) - twilightPenalty(sunAltitudeDeg)
    return limit.coerceIn(FLOOR_NELM, darkNelm)
}

/** Up to ~3 mag loss for a full Moon near the zenith; scales with illumination and altitude. */
private fun moonPenalty(altDeg: Double, illumination: Double): Double {
    if (altDeg <= 0.0) return 0.0
    val altFactor = sin(Math.toRadians(altDeg)).coerceIn(0.0, 1.0)
    return MAX_MOON_PENALTY * illumination.coerceIn(0.0, 1.0) * altFactor
}

/** Zero at/below astronomical twilight (sun ≤ −18°), ramping (quadratic) to full daylight wash-out. */
private fun twilightPenalty(sunAltDeg: Double): Double {
    if (sunAltDeg <= ASTRO_TWILIGHT_DEG) return 0.0
    if (sunAltDeg > 0.0) return MAX_TWILIGHT_PENALTY
    val t = ((sunAltDeg - ASTRO_TWILIGHT_DEG) / (0.0 - ASTRO_TWILIGHT_DEG)).coerceIn(0.0, 1.0)
    return MAX_TWILIGHT_PENALTY * t * t
}

private const val MAX_MOON_PENALTY = 3.0
private const val ASTRO_TWILIGHT_DEG = -18.0
private const val MAX_TWILIGHT_PENALTY = 12.0
private const val FLOOR_NELM = -4.0
