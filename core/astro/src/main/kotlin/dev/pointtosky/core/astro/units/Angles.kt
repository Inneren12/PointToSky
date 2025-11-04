package dev.pointtosky.core.astro.units

import kotlin.math.PI

/** Factor to convert degrees to radians (π / 180). */
const val DEG: Double = PI / 180.0
/** Factor to convert radians to degrees (180 / π). */
const val RAD: Double = 180.0 / PI

/** Degrees → Radians. */
fun degToRad(degrees: Double): Double = degrees * DEG
/** Radians → Degrees. */
fun radToDeg(radians: Double): Double = radians * RAD

/** Normalize degrees to [0°, 360°). */
fun wrapDeg0_360(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0

/** Normalize degrees to (-180°, 180°]. */
fun wrapDegN180_180(degrees: Double): Double {
    val wrapped = wrapDeg0_360(degrees)
    return if (wrapped > 180.0 || (wrapped == 180.0 && degrees < 0.0)) wrapped - 360.0 else wrapped
}

/** Clamp value to [min, max]. */
fun clamp(value: Double, min: Double, max: Double): Double {
    require(min <= max) { "min must be <= max" }
    return value.coerceIn(min, max)
}
