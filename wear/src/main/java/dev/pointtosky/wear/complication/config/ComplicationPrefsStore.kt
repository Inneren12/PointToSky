package dev.pointtosky.wear.complication.config

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.map

private val Context.complicationPrefsDataStore by preferencesDataStore("complication_prefs")

private object ComplicationKeys {
    val AIM_SHOW_DELTA = booleanPreferencesKey("aim_show_delta")
    val AIM_SHOW_PHASE = booleanPreferencesKey("aim_show_phase")
    val TONIGHT_MAG_LIMIT = floatPreferencesKey("tonight_mag_limit")
    val TONIGHT_PREFER_PLANETS = booleanPreferencesKey("tonight_prefer_planets")
}

class ComplicationPrefsStore(private val context: Context) {

    val aimFlow = context.complicationPrefsDataStore.data.map { prefs ->
        AimPrefs(
            showDelta = prefs[ComplicationKeys.AIM_SHOW_DELTA] ?: true,
            showPhase = prefs[ComplicationKeys.AIM_SHOW_PHASE] ?: true,
        )
    }

    val tonightFlow = context.complicationPrefsDataStore.data.map { prefs ->
        TonightPrefs(
            magLimit = prefs[ComplicationKeys.TONIGHT_MAG_LIMIT] ?: 5.5f,
            preferPlanets = prefs[ComplicationKeys.TONIGHT_PREFER_PLANETS] ?: true,
        )
    }

    suspend fun saveAim(prefs: AimPrefs) {
        context.complicationPrefsDataStore.edit { storage ->
            storage[ComplicationKeys.AIM_SHOW_DELTA] = prefs.showDelta
            storage[ComplicationKeys.AIM_SHOW_PHASE] = prefs.showPhase
        }
    }

    suspend fun saveTonight(prefs: TonightPrefs) {
        context.complicationPrefsDataStore.edit { storage ->
            storage[ComplicationKeys.TONIGHT_MAG_LIMIT] = prefs.magLimit
            storage[ComplicationKeys.TONIGHT_PREFER_PLANETS] = prefs.preferPlanets
        }
    }
}
