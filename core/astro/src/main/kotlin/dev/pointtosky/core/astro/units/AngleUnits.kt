package dev.pointtosky.core.astro.units

import kotlin.math.PI

/**
 * Factor for converting decimal degrees to radians (° → rad).
 */
public const val DEG: Double = PI / 180.0

/**
 * Factor for converting radians to decimal degrees (rad → °).
 */
public const val RAD: Double = 180.0 / PI

/**
 * Converts an angle expressed in decimal degrees to radians.
 *
 * @param degrees Angle in decimal degrees.
 * @return Angle in radians.
 */
public fun degToRad(degrees: Double): Double = degrees * DEG

/**
 * Converts an angle expressed in radians to decimal degrees.
 *
 * @param radians Angle in radians.
 * @return Angle in decimal degrees.
 */
public fun radToDeg(radians: Double): Double = radians * RAD

/**
 * Wraps a degree angle into the [0°, 360°) interval.
 *
 * @param degrees Angle in decimal degrees.
 * @return Equivalent angle normalized to the [0°, 360°) range.
 */
public fun wrapDeg0_360(degrees: Double): Double {
    val wrapped = degrees % 360.0
    val normalized = if (wrapped < 0.0) {
        wrapped + 360.0
    } else if (wrapped >= 360.0) {
        wrapped - 360.0
    } else {
        wrapped
    }
    return if (normalized == 0.0) 0.0 else normalized
}

/**
 * Wraps a degree angle into the [-180°, 180°) interval.
 *
 * @param degrees Angle in decimal degrees.
 * @return Equivalent angle normalized to the [-180°, 180°) range.
 */
public fun wrapDegN180_180(degrees: Double): Double {
    val wrapped = wrapDeg0_360(degrees)
    val normalized = if (wrapped >= 180.0) wrapped - 360.0 else wrapped
    return if (normalized == 0.0) 0.0 else normalized
}

/**
 * Clamps a value between the provided limits.
 *
 * @param value Value to clamp.
 * @param min Minimum bound (inclusive).
 * @param max Maximum bound (inclusive).
 * @return [value] constrained to the [`min`, `max`] range.
 */
public fun clamp(value: Double, min: Double, max: Double): Double {
    require(min <= max) { "min must be <= max" }
    return when {
        value < min -> min
        value > max -> max
        else -> value
    }
}
