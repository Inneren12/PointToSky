package dev.pointtosky.wear.complication

import dev.pointtosky.wear.aim.core.AimPhase
import dev.pointtosky.wear.aim.core.AimTarget
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Snapshot persisted for complication rendering.
 */
data class AimStatusSnapshot(
    val timestampMs: Long = 0L,
    val isActive: Boolean = false,
    val dAzDeg: Double? = null,
    val dAltDeg: Double? = null,
    val phase: AimPhase? = null,
    val target: AimStatusTarget? = null,
    val toleranceAzDeg: Double = DEFAULT_TOLERANCE_AZ,
    val toleranceAltDeg: Double = DEFAULT_TOLERANCE_ALT,
) {
    fun closenessPercent(): Float? {
        val az = dAzDeg ?: return null
        val alt = dAltDeg ?: return null
        if (toleranceAzDeg <= 0.0 || toleranceAltDeg <= 0.0) return null
        val azScore = 1.0 - abs(az) / toleranceAzDeg
        val altScore = 1.0 - abs(alt) / toleranceAltDeg
        val combined = min(azScore, altScore)
        if (combined.isNaN()) return null
        return (max(0.0, min(1.0, combined)) * 100.0).toFloat()
    }

    fun displayTitle(fallback: String): String = target?.label ?: fallback

    companion object {
        private const val DEFAULT_TOLERANCE_AZ = 3.0
        private const val DEFAULT_TOLERANCE_ALT = 4.0

        val EMPTY = AimStatusSnapshot()
    }
}

data class AimStatusTarget(
    val kind: AimStatusTargetKind,
    val label: String?,
    val bodyName: String? = null,
    val starId: Int? = null,
    val raDeg: Double? = null,
    val decDeg: Double? = null,
) {
    fun toAimTarget(): AimTarget? =
        when (kind) {
            AimStatusTargetKind.BODY ->
                bodyName?.let { name ->
                    runCatching {
                        dev.pointtosky.core.astro.ephem.Body
                            .valueOf(name)
                    }.getOrNull()
                        ?.let { AimTarget.BodyTarget(it) }
                }
            AimStatusTargetKind.STAR ->
                starId?.let { id ->
                    val eq =
                        if (raDeg != null && decDeg != null) {
                            dev.pointtosky.core.astro.coord
                                .Equatorial(raDeg = raDeg, decDeg = decDeg)
                        } else {
                            null
                        }
                    AimTarget.StarTarget(id, eq)
                }
            AimStatusTargetKind.EQUATORIAL ->
                if (raDeg != null && decDeg != null) {
                    val eq =
                        dev.pointtosky.core.astro.coord
                            .Equatorial(raDeg = raDeg, decDeg = decDeg)
                    AimTarget.EquatorialTarget(eq)
                } else {
                    null
                }
        }
}

enum class AimStatusTargetKind { BODY, STAR, EQUATORIAL }
