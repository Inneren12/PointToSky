package dev.pointtosky.core.astro.projection.camera.prediction

import kotlin.math.PI

/**
 * Radian-angle wraparound helpers for the CAM-2a prediction inputs. These are deliberately separate
 * from [dev.pointtosky.core.astro.units]'s degree-based `wrapDeg0To360`/`wrapDegMinus180To180`: the
 * CAM-2a input contract ([EquatorialStarDirection], [StarProjectionContext]) is radian-first (see
 * their KDoc for why), so wrapping happens once, here, in radians — never via a degrees round-trip.
 */

private const val TWO_PI = 2.0 * PI

/**
 * Wraps [radians] into the canonical **[0, 2π)** interval used for
 * [EquatorialStarDirection.rightAscensionRad]. `2π` itself wraps to `0`; a negative input wraps
 * forward (e.g. `-0.1` → `2π − 0.1`).
 */
fun wrapRadTwoPi(radians: Double): Double {
    val wrapped = radians % TWO_PI
    return if (wrapped < 0.0) wrapped + TWO_PI else wrapped
}

/**
 * Wraps [radians] into the canonical **[-π, π)** interval used for
 * [StarProjectionContext.longitudeRad]. `π` itself wraps to `-π`.
 */
fun wrapRadMinusPiToPi(radians: Double): Double {
    val wrapped = (radians + PI) % TWO_PI
    val nonNegative = if (wrapped < 0.0) wrapped + TWO_PI else wrapped
    return nonNegative - PI
}
