package dev.pointtosky.wear.aim.core

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.ephem.Body

data class AimTolerance(
    val azDeg: Double = 3.0,
    val altDeg: Double = 4.0,
)

/**
 * Acquisition strictness as a user choice. Carries two things:
 *  - [tolerance]: the recommended az/alt preset the settings layer snaps to when this mode is picked
 *    (still tunable afterward via the existing steppers — the controller's tol flows from those).
 *  - [graceResetsHold]: the controller's only mode-dependent bit — whether a drift into the grace
 *    zone (outside the enter box, inside the release box) resets the hold-to-lock timer.
 *
 * Honest caveat (surfaced in the UI): the watch's compass + rotation-vector are only accurate to a
 * few degrees, so even FINDER gets you into a finderscope / red-dot field of view, not eyepiece
 * precision — then you star-hop.
 */
enum class AimMode(val tolerance: AimTolerance, val graceResetsHold: Boolean) {
    /** Looser tolerance; grace-zone time is NOT reset → tremor-tolerant acquisition. Default. */
    NAKED_EYE(AimTolerance(azDeg = 3.0, altDeg = 4.0), graceResetsHold = false),

    /** Tighter tolerance; a drift into the grace zone resets the hold timer → strict acquisition. */
    FINDER(AimTolerance(azDeg = 1.5, altDeg = 2.0), graceResetsHold = true),
}

enum class AimPhase { SEARCHING, IN_TOLERANCE, LOCKED, BELOW_HORIZON, NO_LOCATION }

data class AimState(
    val current: Horizontal,
    val target: Horizontal,
    /** Cos(alt)-corrected cross-track azimuth error in true sky degrees (left/right offset from target).
     *  Positive = target is to the right (clockwise). Not the raw azimuth difference. */
    val dAzDeg: Double,
    /** Along-track altitude error in degrees (target.alt − current.alt). Positive = target is higher. */
    val dAltDeg: Double,
    val phase: AimPhase,
    val confidence: Float,
)

sealed interface AimTarget {
    data class EquatorialTarget(
        val eq: Equatorial,
    ) : AimTarget

    data class BodyTarget(
        val body: Body,
    ) : AimTarget

    /**
     * Цель — звезда по идентификатору каталога (S5).
     *
     * [starId] — числовой ID звезды в каталоге (например, внутренний ID S5).
     * [eq] — опциональные координаты, если они уже известны на стороне отправителя.
     *        Если координаты не переданы, их можно дорезолвить по [starId] в месте применения.
     */
    data class StarTarget(
        val starId: Int,
        val eq: Equatorial? = null,
    ) : AimTarget
}

interface AimController {
    val state: kotlinx.coroutines.flow.StateFlow<AimState>

    fun setTarget(target: AimTarget)

    fun setTolerance(t: AimTolerance)

    fun setHoldToLockMs(ms: Long = 1200)

    fun setMode(mode: AimMode = AimMode.NAKED_EYE)

    fun start()

    fun stop()
}
