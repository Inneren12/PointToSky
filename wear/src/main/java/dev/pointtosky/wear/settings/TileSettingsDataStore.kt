package dev.pointtosky.wear.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pointtosky.wear.tile.tonight.TonightConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.tilePrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tile_settings"
)

class TileSettingsDataStore(
    private val context: Context
) {
    // Ключи
    private val KEY_MAG_LIMIT = doublePreferencesKey("tile.magLimit")
    private val KEY_MIN_ALT   = doublePreferencesKey("tile.minAltDeg")
    private val KEY_MAX_ITEMS = intPreferencesKey("tile.maxItems")
    private val KEY_PREF_PLAN = booleanPreferencesKey("tile.preferPlanets")

    // Значения по умолчанию
    private val DEF_MAG_LIMIT = 2.0
    private val DEF_MIN_ALT   = 15.0
    private val DEF_MAX_ITEMS = 3
    private val DEF_PREF_PLAN = true

    // Flows
    val magLimitFlow: Flow<Double> =
        context.tilePrefsDataStore.data.map { it[KEY_MAG_LIMIT] ?: DEF_MAG_LIMIT }
    val minAltDegFlow: Flow<Double> =
        context.tilePrefsDataStore.data.map { it[KEY_MIN_ALT] ?: DEF_MIN_ALT }
    val maxItemsFlow: Flow<Int> =
        context.tilePrefsDataStore.data.map { it[KEY_MAX_ITEMS] ?: DEF_MAX_ITEMS }
    val preferPlanetsFlow: Flow<Boolean> =
        context.tilePrefsDataStore.data.map { it[KEY_PREF_PLAN] ?: DEF_PREF_PLAN }

    // Mutators
    suspend fun setMagLimit(value: Double) =
        context.tilePrefsDataStore.edit { it[KEY_MAG_LIMIT] = value }
    suspend fun setMinAltDeg(value: Double) =
        context.tilePrefsDataStore.edit { it[KEY_MIN_ALT] = value }
    suspend fun setMaxItems(value: Int) =
        context.tilePrefsDataStore.edit { it[KEY_MAX_ITEMS] = value }
    suspend fun setPreferPlanets(value: Boolean) =
        context.tilePrefsDataStore.edit { it[KEY_PREF_PLAN] = value }

    // Снимок конфигурации для провайдера
    suspend fun readConfig(): TonightConfig {
        val mag = magLimitFlow.first()
        val alt = minAltDegFlow.first()
        val max = maxItemsFlow.first()
        val pref= preferPlanetsFlow.first()
        return TonightConfig(magLimit = mag, minAltDeg = alt, maxItems = max, preferPlanets = pref).clamped()
    }
}
