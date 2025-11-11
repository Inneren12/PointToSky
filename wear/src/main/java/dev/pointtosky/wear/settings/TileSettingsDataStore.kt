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
    name = "tile_settings",
)

class TileSettingsDataStore(
    private val context: Context,
) {
    // Ключи
    private val keyMagLimit = doublePreferencesKey("tile.magLimit")
    private val keyMinAlt = doublePreferencesKey("tile.minAltDeg")
    private val keyMaxItems = intPreferencesKey("tile.maxItems")
    private val keyPrefPlan = booleanPreferencesKey("tile.preferPlanets")

    // Значения по умолчанию
    private val defMagLimit = 2.0
    private val defMinAlt = 15.0
    private val defMaxItems = 3
    private val defPrefPlan = true

    // Flows
    val magLimitFlow: Flow<Double> =
        context.tilePrefsDataStore.data.map { it[keyMagLimit] ?: defMagLimit }
    val minAltDegFlow: Flow<Double> =
        context.tilePrefsDataStore.data.map { it[keyMinAlt] ?: defMinAlt }
    val maxItemsFlow: Flow<Int> =
        context.tilePrefsDataStore.data.map { it[keyMaxItems] ?: defMaxItems }
    val preferPlanetsFlow: Flow<Boolean> =
        context.tilePrefsDataStore.data.map { it[keyPrefPlan] ?: defPrefPlan }

    // Mutators
    suspend fun setMagLimit(value: Double) = context.tilePrefsDataStore.edit { it[keyMagLimit] = value }
    suspend fun setMinAltDeg(value: Double) = context.tilePrefsDataStore.edit { it[keyMinAlt] = value }
    suspend fun setMaxItems(value: Int) = context.tilePrefsDataStore.edit { it[keyMaxItems] = value }
    suspend fun setPreferPlanets(value: Boolean) = context.tilePrefsDataStore.edit { it[keyPrefPlan] = value }

    // Снимок конфигурации для провайдера
    suspend fun readConfig(): TonightConfig {
        val mag = magLimitFlow.first()
        val alt = minAltDegFlow.first()
        val max = maxItemsFlow.first()
        val pref = preferPlanetsFlow.first()
        return TonightConfig(magLimit = mag, minAltDeg = alt, maxItems = max, preferPlanets = pref).clamped()
    }
}
