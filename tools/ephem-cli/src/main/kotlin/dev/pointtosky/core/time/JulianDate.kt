@file:Suppress("unused")

package dev.pointtosky.core.time

import java.time.Instant

private const val JULIAN_DAY_AT_UNIX_EPOCH = 2440587.5
private const val SECONDS_PER_DAY = 86_400.0
private const val JULIAN_DAY_AT_J2000 = 2_451_545.0
private const val JULIAN_CENTURY_DAYS = 36_525.0
private const val SECONDS_PER_DAY_LONG = 86_400L

fun instantToJulianDay(instant: Instant): Double {
    val epochSeconds = instant.epochSecond
    val days = Math.floorDiv(epochSeconds, SECONDS_PER_DAY_LONG).toDouble()
    val remainingSeconds = Math.floorMod(epochSeconds, SECONDS_PER_DAY_LONG).toDouble()
    val fractionalSeconds = instant.nano / 1_000_000_000.0
    val dayFraction = (remainingSeconds + fractionalSeconds) / SECONDS_PER_DAY
    return days + dayFraction + JULIAN_DAY_AT_UNIX_EPOCH
}

fun instantToJulianCenturies(instant: Instant): Double {
    val julianDay = instantToJulianDay(instant)
    return (julianDay - JULIAN_DAY_AT_J2000) / JULIAN_CENTURY_DAYS
}

// TODO: формулы LST/GMST перенести в S4 (astro-core).
