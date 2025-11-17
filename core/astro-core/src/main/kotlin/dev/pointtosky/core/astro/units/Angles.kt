package dev.pointtosky.core.astro.units

import dev.pointtosky.core.astro.coord.Equatorial
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

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
fun wrapDeg0To360(degrees: Double): Double =
    ((degrees % 360.0) + 360.0) % 360.0

/** Нормализует к диапазону (−180°, 180°] (180 включительно, −180 исключён). */
fun wrapDegMinus180To180(degrees: Double): Double {
    // базовый wrap в [-180, 180)
    var y = ((degrees + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    // переводим -180 в +180 → итог: (−180, 180]
    if (y == -0.0) y = 0.0
    return y
}

/** Кратчайшая дуга по долготе/часовому углу в градусах (для азимута/RA-дельты). */
fun shortestArcDeg(deltaDeg: Double): Double = wrapDegMinus180To180(deltaDeg)

/** Угловое расстояние между двумя точками на сфере в экваториальных координатах (в градусах). */
fun angularSeparationEqDeg(a: Equatorial, b: Equatorial): Double {
    val dRA = Math.toRadians(shortestArcDeg(b.raDeg - a.raDeg))
    val dec1 = Math.toRadians(a.decDeg)
    val dec2 = Math.toRadians(b.decDeg)
    val cosd = sin(dec1) * sin(dec2) + cos(dec1) * cos(dec2) * cos(dRA)
    val clamped = cosd.coerceIn(-1.0, 1.0)
    return Math.toDegrees(acos(clamped))
}

/** Кратчайшая дуга по азимуту в градусах (в диапазоне (−180, 180]). */
fun deltaAzimuthShortestDeg(currentAzDeg: Double, targetAzDeg: Double): Double =
    wrapDegMinus180To180(targetAzDeg - currentAzDeg)

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
