@file:JvmName("Angles")

package dev.pointtosky.core.astro.units

import kotlin.math.PI
import kotlin.math.abs

/**
 * Converts an angle measured in decimal degrees to radians.
 *
 * @receiver angle in degrees.
 * @return angle in radians.
 */
fun Double.degToRad(): Double = degToRad(this)

/**
 * Converts an angle measured in radians to decimal degrees.
 *
 * @receiver angle in radians.
 * @return angle in degrees.
 */
fun Double.radToDeg(): Double = radToDeg(this)

/** Constant factor multiplying degrees to obtain radians. */
const val DEG_TO_RAD: Double = DEG

/** Constant factor multiplying radians to obtain degrees. */
const val RAD_TO_DEG: Double = RAD

private const val FULL_TURN_RAD: Double = 2.0 * PI
private const val HALF_TURN_RAD: Double = PI

private fun normalizeRadians(value: Double, period: Double): Double {
    if (period <= 0.0 || value.isNaN()) return value
    val remainder = value % period
    return if (remainder < 0.0) remainder + period else remainder
}

/**
 * Normalizes an angle measured in degrees to the [0°, 360°) interval.
 *
 * @receiver angle in degrees.
 * @return equivalent angle in the range [0°, 360°).
 */
fun Double.wrapDegrees(): Double = wrapDeg0To360(this)

/**
 * Normalizes an angle measured in degrees to the [-180°, +180°) interval.
 *
 * @receiver angle in degrees.
 * @return equivalent angle in the range [-180°, +180°).
 */
fun Double.wrapDegreesSigned(): Double = wrapDegMinus180To180(this)

/**
 * Normalizes an angle measured in radians to the [0, 2π) interval.
 *
 * @receiver angle in radians.
 * @return equivalent angle in the range [0, 2π).
 */
fun Double.wrapRadians(): Double = normalizeRadians(this, FULL_TURN_RAD)

/**
 * Normalizes an angle measured in radians to the [-π, +π) interval.
 *
 * @receiver angle in radians.
 * @return equivalent angle in the range [-π, +π).
 */
fun Double.wrapRadiansSigned(): Double {
    val wrapped = wrapRadians()
    return when {
        wrapped > HALF_TURN_RAD -> wrapped - FULL_TURN_RAD
        wrapped == HALF_TURN_RAD && this < 0.0 -> -HALF_TURN_RAD
        else -> wrapped
    }
}

/**
 * Clamps [this] value so that it falls within the inclusive range defined by [lowerBound] and [upperBound].
 *
 * @param lowerBound lower inclusive limit (same units as [this]).
 * @param upperBound upper inclusive limit (same units as [this]).
 * @return [lowerBound] if [this] is less, [upperBound] if [this] is greater, otherwise [this].
 * @throws IllegalArgumentException if [lowerBound] is greater than [upperBound].
 */
fun Double.clamp(lowerBound: Double, upperBound: Double): Double = clamp(this, lowerBound, upperBound)

/**
 * Returns the absolute difference between two angles in degrees, wrapped into [0°, 180°].
 *
 * @param other angle in degrees.
 * @return angular separation in degrees in the range [0°, 180°].
 */
fun Double.angularSeparationDegrees(other: Double): Double {
    val diff = (this - other).wrapDegreesSigned()
    return abs(diff)
}

/**
 * Returns the absolute difference between two angles in radians, wrapped into [0, π].
 *
 * @param other angle in radians.
 * @return angular separation in radians in the range [0, π].
 */
fun Double.angularSeparationRadians(other: Double): Double {
    val diff = (this - other).wrapRadiansSigned()
    return abs(diff)
}
