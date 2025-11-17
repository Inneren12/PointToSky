package dev.pointtosky.core.astro.time

import dev.pointtosky.core.astro.coord.Sidereal
import dev.pointtosky.core.astro.units.wrapDeg0To360
import java.time.Instant

private const val JULIAN_DAY_AT_J2000 = 2_451_545.0
private const val JULIAN_CENTURY_DAYS = 36_525.0

/**
 * Computes the Greenwich mean sidereal time (GMST) in degrees for a given Julian day.
 *
 * The implementation follows the IAU-1982 polynomial published by Meeus (1998, §12). The
 * approximation is accurate to within ≈0.1° (~24 sidereal seconds) inside a few centuries around
 * J2000 and stays <0.2° (~48 sidereal seconds) even closer to the horizon, which is sufficient for
 * the MVP pointing requirements. This corresponds to a relative error <5×10⁻⁴ of a full rotation.
 *
 * The returned angle measures the rotation of the Earth with respect to the ICRF: `0°` aligns the
 * Greenwich meridian with the celestial equator's ascending node and increases eastward.
 *
 * @param julianDay Terrestrial Time expressed as a Julian day number.
 * @return Greenwich mean sidereal time normalized to the [0°, 360°) interval.
 */
fun gmstDeg(julianDay: Double): Double {
    val deltaDays = julianDay - JULIAN_DAY_AT_J2000
    val centuries = deltaDays / JULIAN_CENTURY_DAYS
    val theta = 280.460_618_37 +
        360.985_647_366_29 * deltaDays +
        0.000_387_933 * centuries * centuries -
        (centuries * centuries * centuries) / 38_710_000.0
    return wrapDeg0To360(theta)
}

/**
 * Converts Greenwich mean sidereal time to the local sidereal time for the provided longitude.
 *
 * Longitudes are positive eastward from Greenwich. The returned value is normalized to [0°, 360°).
 *
 * @param gmstDeg Greenwich mean sidereal time in decimal degrees.
 * @param longitudeDeg Observer's geographic longitude in decimal degrees (east positive).
 * @return Local sidereal time at the observer's meridian in decimal degrees.
 */
fun lstDeg(gmstDeg: Double, longitudeDeg: Double): Double = wrapDeg0To360(gmstDeg + longitudeDeg)

/**
 * Computes the local sidereal time for an [instant] and longitude.
 *
 * @param instant Moment for which the sidereal time is required (UTC).
 * @param longitudeDeg Observer's geographic longitude in decimal degrees (east positive).
 * @return [Sidereal] time at the observer's meridian, normalized to [0°, 360°).
 */
fun lstAt(instant: Instant, longitudeDeg: Double): Sidereal {
    val julianDay = instantToJulianDay(instant)
    val gmst = gmstDeg(julianDay)
    val lst = lstDeg(gmst, longitudeDeg)
    return Sidereal(lst)
}
