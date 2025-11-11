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
import androidx.datastore.preferences.core.booleanPreferencesKey as boolKey

private val Context.aimIdentifyPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "aim_identify_prefs",
)

class AimIdentifySettingsDataStore(
    private val context: Context,
) {
    // Keys
    private val keyAzTol = doublePreferencesKey("aim.azTol")
    private val keyAltTol = doublePreferencesKey("aim.altTol")
    private val keyHoldMs = longPreferencesKey("aim.holdMs")
    private val keyHaptic = booleanPreferencesKey("aim.hapticEnabled")
    private val keyMagLimit = doublePreferencesKey("identify.magLimit")
    private val keyRadius = doublePreferencesKey("identify.radiusDeg")

    // Defaults (per spec)
    private val defAzTol = 3.0
    private val defAltTol = 4.0
    private val defHoldMs = 1200L
    private val defHaptic = true
    private val defMagLimit = 5.5
    private val defRadius = 5.0
    private val defTileMirror = false

    // Flows
    val aimAzTolFlow: Flow<Double> =
        context.aimIdentifyPrefsDataStore.data.map { it[keyAzTol] ?: defAzTol }
    val aimAltTolFlow: Flow<Double> =
        context.aimIdentifyPrefsDataStore.data.map { it[keyAltTol] ?: defAltTol }
    val aimHoldMsFlow: Flow<Long> =
        context.aimIdentifyPrefsDataStore.data.map { it[keyHoldMs] ?: defHoldMs }
    val aimHapticEnabledFlow: Flow<Boolean> =
        context.aimIdentifyPrefsDataStore.data.map { it[keyHaptic] ?: defHaptic }
    val identifyMagLimitFlow: Flow<Double> =
        context.aimIdentifyPrefsDataStore.data.map { it[keyMagLimit] ?: defMagLimit }
    val identifyRadiusDegFlow: Flow<Double> =
        context.aimIdentifyPrefsDataStore.data.map { it[keyRadius] ?: defRadius }

    // S7.E: Mirroring toggle
    private val keyTileMirror = boolKey("tile.mirroringEnabled")
    val tileMirroringEnabledFlow: Flow<Boolean> =
        context.aimIdentifyPrefsDataStore.data.map { it[keyTileMirror] ?: defTileMirror }

    // Setters
    suspend fun setAimAzTol(value: Double) = context.aimIdentifyPrefsDataStore.edit { it[keyAzTol] = value }
    suspend fun setAimAltTol(value: Double) = context.aimIdentifyPrefsDataStore.edit { it[keyAltTol] = value }
    suspend fun setAimHoldMs(value: Long) = context.aimIdentifyPrefsDataStore.edit { it[keyHoldMs] = value }
    suspend fun setAimHapticEnabled(value: Boolean) = context.aimIdentifyPrefsDataStore.edit { it[keyHaptic] = value }
    suspend fun setIdentifyMagLimit(value: Double) = context.aimIdentifyPrefsDataStore.edit { it[keyMagLimit] = value }
    suspend fun setIdentifyRadiusDeg(value: Double) = context.aimIdentifyPrefsDataStore.edit { it[keyRadius] = value }

    // S7.E
    suspend fun setTileMirroringEnabled(value: Boolean) = context.aimIdentifyPrefsDataStore.edit {
        it[keyTileMirror] = value
    }
}
