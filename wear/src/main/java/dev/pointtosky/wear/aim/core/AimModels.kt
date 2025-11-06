package dev.pointtosky.wear.aim.core

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.ephem.Body

data class AimTolerance(val azDeg: Double = 3.0, val altDeg: Double = 4.0)

enum class AimPhase { SEARCHING, IN_TOLERANCE, LOCKED }

data class AimState(
    val current: Horizontal,
    val target: Horizontal,
    val dAzDeg: Double,
    val dAltDeg: Double,
    val phase: AimPhase,
    val confidence: Float,
)

sealed interface AimTarget {
    data class EquatorialTarget(val eq: Equatorial) : AimTarget
    data class BodyTarget(val body: Body) : AimTarget
    // TODO: data class StarTarget(val starId: Int): AimTarget  // после S5
}

interface AimController {
    val state: kotlinx.coroutines.flow.StateFlow<AimState>

    fun setTarget(target: AimTarget)

    fun setTolerance(t: AimTolerance)

    fun setHoldToLockMs(ms: Long = 1200)

    fun start()

    fun stop()
}
