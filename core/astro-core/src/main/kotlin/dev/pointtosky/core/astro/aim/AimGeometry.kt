package dev.pointtosky.core.astro.aim

import dev.pointtosky.core.astro.coord.Horizontal
import kotlin.math.abs
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
