package dev.pointtosky.core.astro.aim

import dev.pointtosky.core.astro.coord.Horizontal
import kotlin.math.abs
import kotlin.math.cos
import dev.pointtosky.core.astro.units.wrapDegMinus180To180

data class AimError(
    val dAzDeg: Double,
    val dAltDeg: Double,
    val inTolerance: Boolean,
)

data class AimTolerance(
    val azimuthDeg: Double = 3.0,
    val altitudeDeg: Double = 4.0,
)

/**
 * Local pointing error between the held ray ([current]) and the [target], decomposed for guidance.
 *
 * crossTrackDeg — signed east-west sky error, cos(alt)-corrected: how far, in true sky degrees, the ray is
 *   left/right of the target. Positive = target is clockwise (higher azimuth). This is the value the
 *   azimuth tolerance and the horizontal arrow must use; raw azimuth degrees overstate the error and
 *   diverge near the zenith.
 * alongTrackDeg — signed altitude error (target.alt − current.alt). Positive = target is higher.
 */
data class AimDelta(
    val crossTrackDeg: Double,
    val alongTrackDeg: Double,
)

fun aimDelta(current: Horizontal, target: Horizontal): AimDelta {
    val dAzRaw = wrapDegMinus180To180(target.azDeg - current.azDeg)
    val dAlt = target.altDeg - current.altDeg
    val cross = dAzRaw * cos(Math.toRadians(current.altDeg)) // cos(alt) ∈ [0,1] over [-90°,90°] → sign kept
    return AimDelta(crossTrackDeg = cross, alongTrackDeg = dAlt)
}

fun aimError(current: Horizontal, target: Horizontal, tol: AimTolerance): AimError {
    val wrappedAzDiff = wrapDegMinus180To180(target.azDeg - current.azDeg)
    val altDiff = target.altDeg - current.altDeg

    val inTolerance = abs(wrappedAzDiff) <= tol.azimuthDeg && abs(altDiff) <= tol.altitudeDeg

    return AimError(
        dAzDeg = wrappedAzDiff,
        dAltDeg = altDiff,
        inTolerance = inTolerance,
    )
}
