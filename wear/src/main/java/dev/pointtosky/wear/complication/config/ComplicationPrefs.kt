package dev.pointtosky.wear.complication.config

/** Preferences for Aim complication configuration. */
data class AimPrefs(
    val showDelta: Boolean,
    val showPhase: Boolean,
)

/** Preferences for Tonight complication configuration. */
data class TonightPrefs(
    val magLimit: Float,
    val preferPlanets: Boolean,
)
