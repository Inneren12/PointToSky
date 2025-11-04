package dev.pointtosky.core.astro.units

import kotlin.math.PI

/**
 * Factor to convert degrees to radians.
 *
 * One degree equals [DEG] radians (π / 180).
 */
const val DEG: Double = PI / 180.0

/**
 * Factor to convert radians to degrees.
 *
 * One radian equals [RAD] degrees (180 / π).
 */
const val RAD: Double = 180.0 / PI

/**
 * Converts an angle from degrees to radians.
 *
 * @param degrees Angle in degrees.
 * @return Angle in radians.
 */
fun degToRad(degrees: Double): Double = degrees * DEG

/**
 * Converts an angle from radians to degrees.
 *
 * @param radians Angle in radians.
 * @return Angle in degrees.
 */
fun radToDeg(radians: Double): Double = radians * RAD

/**
 * Wraps an angle in degrees to the [0°, 360°) range.
 *
 * @param degrees Angle in degrees.
 * @return Angle normalized to [0°, 360°).
 */
fun wrapDeg0_360(degrees: Double): Double =
    ((degrees % 360.0) + 360.0) % 360.0

/**
 * Wraps an angle in degrees to the (-180°, 180°] range.
 *
 * @param degrees Angle in degrees.
 * @return Angle normalized to (-180°, 180°].
 */
fun wrapDegN180_180(degrees: Double): Double {
    val wrapped = wrapDeg0_360(degrees)
    return if (wrapped > 180.0 || (wrapped == 180.0 && degrees < 0.0)) {
        wrapped - 360.0
    } else {
        wrapped
    }
}

/**
 * Clamps a value to the [min, max] range.
 *
 * @param value Value to clamp.
 * @param min Lower inclusive bound.
 * @param max Upper inclusive bound.
 * @return [value] limited to the [min, max] interval.
 * @throws IllegalArgumentException if [min] is greater than [max].
 */
fun clamp(value: Double, min: Double, max: Double): Double {
    require(min <= max) { "min must be <= max" }
    return value.coerceIn(min, max)
}
