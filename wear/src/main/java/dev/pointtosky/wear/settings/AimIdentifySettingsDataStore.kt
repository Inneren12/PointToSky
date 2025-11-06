package dev.pointtosky.wear.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.aimIdentifyPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "aim_identify_prefs"
)

class AimIdentifySettingsDataStore(
    private val context: Context
) {
    // Keys
    private val KEY_AZ_TOL = doublePreferencesKey("aim.azTol")
    private val KEY_ALT_TOL = doublePreferencesKey("aim.altTol")
    private val KEY_HOLD_MS = longPreferencesKey("aim.holdMs")
    private val KEY_HAPTIC = booleanPreferencesKey("aim.hapticEnabled")
    private val KEY_MAG_LIMIT = doublePreferencesKey("identify.magLimit")
    private val KEY_RADIUS = doublePreferencesKey("identify.radiusDeg")

    // Defaults (per spec)
    private val DEF_AZ_TOL = 3.0
    private val DEF_ALT_TOL = 4.0
    private val DEF_HOLD_MS = 1200L
    private val DEF_HAPTIC = true
    private val DEF_MAG_LIMIT = 5.5
    private val DEF_RADIUS = 5.0

    // Flows
    val aimAzTolFlow: Flow<Double> =
        context.aimIdentifyPrefsDataStore.data.map { it[KEY_AZ_TOL] ?: DEF_AZ_TOL }
    val aimAltTolFlow: Flow<Double> =
        context.aimIdentifyPrefsDataStore.data.map { it[KEY_ALT_TOL] ?: DEF_ALT_TOL }
    val aimHoldMsFlow: Flow<Long> =
        context.aimIdentifyPrefsDataStore.data.map { it[KEY_HOLD_MS] ?: DEF_HOLD_MS }
    val aimHapticEnabledFlow: Flow<Boolean> =
        context.aimIdentifyPrefsDataStore.data.map { it[KEY_HAPTIC] ?: DEF_HAPTIC }
    val identifyMagLimitFlow: Flow<Double> =
        context.aimIdentifyPrefsDataStore.data.map { it[KEY_MAG_LIMIT] ?: DEF_MAG_LIMIT }
    val identifyRadiusDegFlow: Flow<Double> =
        context.aimIdentifyPrefsDataStore.data.map { it[KEY_RADIUS] ?: DEF_RADIUS }

    // Setters
    suspend fun setAimAzTol(value: Double) = context.aimIdentifyPrefsDataStore.edit { it[KEY_AZ_TOL] = value }
    suspend fun setAimAltTol(value: Double) = context.aimIdentifyPrefsDataStore.edit { it[KEY_ALT_TOL] = value }
    suspend fun setAimHoldMs(value: Long) = context.aimIdentifyPrefsDataStore.edit { it[KEY_HOLD_MS] = value }
    suspend fun setAimHapticEnabled(value: Boolean) = context.aimIdentifyPrefsDataStore.edit { it[KEY_HAPTIC] = value }
    suspend fun setIdentifyMagLimit(value: Double) = context.aimIdentifyPrefsDataStore.edit { it[KEY_MAG_LIMIT] = value }
    suspend fun setIdentifyRadiusDeg(value: Double) = context.aimIdentifyPrefsDataStore.edit { it[KEY_RADIUS] = value }
}
