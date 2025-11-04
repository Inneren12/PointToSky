package dev.pointtosky.core.astro.aim

import dev.pointtosky.core.astro.coord.Horizontal
import kotlin.math.abs

public data class AimError(
    val dAzDeg: Double,
    val dAltDeg: Double,
    val inTolerance: Boolean,
)

public data class AimTolerance(
    val azimuthDeg: Double = 3.0,
    val altitudeDeg: Double = 4.0,
)

public fun aimError(current: Horizontal, target: Horizontal, tol: AimTolerance): AimError {
    val rawAzDiff = target.azDeg - current.azDeg
    val wrappedAzDiff = normalizeAzimuthDelta(rawAzDiff)
    val altDiff = target.altDeg - current.altDeg

    val inTolerance = abs(wrappedAzDiff) <= tol.azimuthDeg && abs(altDiff) <= tol.altitudeDeg

    return AimError(
        dAzDeg = wrappedAzDiff,
        dAltDeg = altDiff,
        inTolerance = inTolerance,
    )
}

private fun normalizeAzimuthDelta(deltaDeg: Double): Double {
    var normalized = deltaDeg % 360.0
    if (normalized < -180.0) {
        normalized += 360.0
    } else if (normalized > 180.0) {
        normalized -= 360.0
    }
    return normalized
}
