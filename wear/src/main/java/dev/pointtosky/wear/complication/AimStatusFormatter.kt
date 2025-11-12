package dev.pointtosky.wear.complication

import android.content.Context
import dev.pointtosky.wear.R
import dev.pointtosky.wear.aim.core.AimPhase
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

class AimStatusFormatter(context: Context) {
    private val resources = context.resources
    private val locale: Locale = resources.configuration.locales[0]
    private val degreeFormat: NumberFormat =
        NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 1
            minimumFractionDigits = 0
            isGroupingUsed = false
        }

    fun shortText(
        deltaAzDeg: Double?,
        deltaAltDeg: Double?,
    ): String? {
        val az = deltaAzDeg?.let { formatAz(it) }
        val alt = deltaAltDeg?.let { formatAlt(it) }
        return if (az != null && alt != null) "$az $alt" else null
    }

    fun phaseLabel(phase: AimPhase?): String =
        when (phase) {
            AimPhase.SEARCHING -> resources.getString(R.string.comp_aim_status_phase_searching)
            AimPhase.IN_TOLERANCE -> resources.getString(R.string.comp_aim_status_phase_in_tolerance)
            AimPhase.LOCKED -> resources.getString(R.string.comp_aim_status_phase_locked)
            null -> resources.getString(R.string.comp_aim_status_phase_inactive)
        }

    fun contentDescription(snapshot: AimStatusSnapshot): String {
        return if (!snapshot.isActive) {
            resources.getString(R.string.comp_aim_status_content_no_target)
        } else {
            val phaseText = phaseLabel(snapshot.phase)
            val title = snapshot.target?.label ?: resources.getString(R.string.comp_aim_status_title_default)
            val horizontal = descriptionComponent(snapshot.dAzDeg, Axis.HORIZONTAL)
            val vertical = descriptionComponent(snapshot.dAltDeg, Axis.VERTICAL)
            resources.getString(
                R.string.comp_aim_status_content_active,
                phaseText,
                title,
                horizontal ?: resources.getString(R.string.comp_aim_status_offset_unknown),
                vertical ?: resources.getString(R.string.comp_aim_status_offset_unknown),
            )
        }
    }

    fun descriptionComponent(
        deltaDeg: Double?,
        axis: Axis,
    ): String? {
        val value = deltaDeg ?: return null
        val directionRes =
            when (axis) {
                Axis.HORIZONTAL ->
                    when {
                        value > THRESHOLD_DEG -> R.string.comp_aim_status_direction_right
                        value < -THRESHOLD_DEG -> R.string.comp_aim_status_direction_left
                        else -> R.string.comp_aim_status_direction_center
                    }
                Axis.VERTICAL ->
                    when {
                        value > THRESHOLD_DEG -> R.string.comp_aim_status_direction_up
                        value < -THRESHOLD_DEG -> R.string.comp_aim_status_direction_down
                        else -> R.string.comp_aim_status_direction_level
                    }
            }
        val magnitude = degreeFormat.format(abs(value))
        return resources.getString(
            R.string.comp_aim_status_offset_template,
            magnitude,
            resources.getString(directionRes),
        )
    }

    private fun formatAz(delta: Double): String {
        val arrow =
            when {
                delta > THRESHOLD_DEG -> "→"
                delta < -THRESHOLD_DEG -> "←"
                else -> "↔"
            }
        return arrow + degreeFormat.format(abs(delta)) + DEGREE
    }

    private fun formatAlt(delta: Double): String {
        val arrow =
            when {
                delta > THRESHOLD_DEG -> "↑"
                delta < -THRESHOLD_DEG -> "↓"
                else -> "↕"
            }
        return arrow + degreeFormat.format(abs(delta)) + DEGREE
    }

    companion object {
        private const val DEGREE = "°"
        private const val THRESHOLD_DEG = 0.2
    }

    enum class Axis { HORIZONTAL, VERTICAL }
}
