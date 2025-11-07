package dev.pointtosky.mobile.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map

enum class LocationMode {
    AUTO,
    MANUAL,
}

data class MobileSettingsState(
    val mirrorEnabled: Boolean = false,
    val arEnabled: Boolean = false,
    val locationMode: LocationMode = LocationMode.AUTO,
    val redactPayloads: Boolean = true,
)

interface MobileSettings {
    val mirrorEnabledFlow: Flow<Boolean>
    val arEnabledFlow: Flow<Boolean>
    val locationModeFlow: Flow<LocationMode>
    val redactPayloadsFlow: Flow<Boolean>
    val state: Flow<MobileSettingsState>

    suspend fun setMirrorEnabled(enabled: Boolean)
    suspend fun setArEnabled(enabled: Boolean)
    suspend fun setLocationMode(mode: LocationMode)
    suspend fun setRedactPayloads(enabled: Boolean)

    companion object
}

private class MobileSettingsDataStore(
    private val dataStore: DataStore<Preferences>,
) : MobileSettings {

    override val mirrorEnabledFlow: Flow<Boolean> = dataStore.data
        .handleErrors()
        .map { preferences -> preferences[MIRROR_ENABLED_KEY] ?: false }

    override val arEnabledFlow: Flow<Boolean> = dataStore.data
        .handleErrors()
        .map { preferences -> preferences[AR_ENABLED_KEY] ?: false }

    override val locationModeFlow: Flow<LocationMode> = dataStore.data
        .handleErrors()
        .map { preferences ->
            val raw = preferences[LOCATION_MODE_KEY]
            raw?.let { runCatching { LocationMode.valueOf(it.uppercase()) }.getOrNull() }
                ?: LocationMode.AUTO
        }

    override val redactPayloadsFlow: Flow<Boolean> = dataStore.data
        .handleErrors()
        .map { preferences -> preferences[REDACT_PAYLOADS_KEY] ?: true }

    override val state: Flow<MobileSettingsState> = combine(
        mirrorEnabledFlow,
        arEnabledFlow,
        locationModeFlow,
        redactPayloadsFlow,
    ) { mirror, ar, mode, redact ->
        MobileSettingsState(
            mirrorEnabled = mirror,
            arEnabled = ar,
            locationMode = mode,
            redactPayloads = redact,
        )
    }

    override suspend fun setMirrorEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[MIRROR_ENABLED_KEY] = enabled
        }
    }

    override suspend fun setArEnabled(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[AR_ENABLED_KEY] = enabled
        }
    }

    override suspend fun setLocationMode(mode: LocationMode) {
        dataStore.edit { preferences ->
            preferences[LOCATION_MODE_KEY] = mode.name.lowercase()
        }
    }

    override suspend fun setRedactPayloads(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[REDACT_PAYLOADS_KEY] = enabled
        }
    }

    private fun Flow<Preferences>.handleErrors(): Flow<Preferences> =
        catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }

    companion object {
        private val MIRROR_ENABLED_KEY = booleanPreferencesKey("mirror.enabled")
        private val AR_ENABLED_KEY = booleanPreferencesKey("ar.enabled")
        private val LOCATION_MODE_KEY = stringPreferencesKey("location.mode")
        private val REDACT_PAYLOADS_KEY = booleanPreferencesKey("privacy.redactPayloads")
    }
}

private val Context.mobileSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "mobile_settings",
)

fun MobileSettings.Companion.from(context: Context): MobileSettings {
    return MobileSettingsDataStore(context.applicationContext.mobileSettingsDataStore)
}
