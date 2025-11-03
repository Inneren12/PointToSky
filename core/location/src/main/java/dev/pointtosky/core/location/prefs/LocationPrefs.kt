package dev.pointtosky.core.location.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dev.pointtosky.core.location.model.GeoPoint
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

/**
 * Abstraction over preference storage for location-related overrides.
 */
interface LocationPrefs {
    val manualPointFlow: Flow<GeoPoint?>

    val usePhoneFallbackFlow: Flow<Boolean>

    val shareLocationWithWatchFlow: Flow<Boolean>

    suspend fun setManual(point: GeoPoint?)

    suspend fun setUsePhoneFallback(usePhoneFallback: Boolean)

    suspend fun setShareLocationWithWatch(share: Boolean)

    companion object
}

class DataStoreLocationPrefs(
    private val dataStore: DataStore<Preferences>,
) : LocationPrefs {

    override val manualPointFlow: Flow<GeoPoint?> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            val lat = preferences[MANUAL_LAT_KEY]
            val lon = preferences[MANUAL_LON_KEY]
            if (lat != null && lon != null) {
                GeoPoint(latDeg = lat, lonDeg = lon)
            } else {
                null
            }
        }

    override val usePhoneFallbackFlow: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            preferences[USE_PHONE_FALLBACK_KEY] ?: false
        }

    override val shareLocationWithWatchFlow: Flow<Boolean> = dataStore.data
        .catch { error ->
            if (error is IOException) {
                emit(emptyPreferences())
            } else {
                throw error
            }
        }
        .map { preferences ->
            preferences[SHARE_LOCATION_WITH_WATCH_KEY] ?: false
        }

    override suspend fun setManual(point: GeoPoint?) {
        dataStore.edit { preferences ->
            if (point == null) {
                preferences.remove(MANUAL_LAT_KEY)
                preferences.remove(MANUAL_LON_KEY)
            } else {
                preferences[MANUAL_LAT_KEY] = point.latDeg
                preferences[MANUAL_LON_KEY] = point.lonDeg
            }
        }
    }

    override suspend fun setUsePhoneFallback(usePhoneFallback: Boolean) {
        dataStore.edit { preferences ->
            preferences[USE_PHONE_FALLBACK_KEY] = usePhoneFallback
        }
    }

    override suspend fun setShareLocationWithWatch(share: Boolean) {
        dataStore.edit { preferences ->
            preferences[SHARE_LOCATION_WITH_WATCH_KEY] = share
        }
    }

    companion object {
        private val MANUAL_LAT_KEY = doublePreferencesKey("manual_lat")
        private val MANUAL_LON_KEY = doublePreferencesKey("manual_lon")
        private val USE_PHONE_FALLBACK_KEY = booleanPreferencesKey("use_phone_fallback")
        private val SHARE_LOCATION_WITH_WATCH_KEY = booleanPreferencesKey("share_location_with_watch")
    }
}

val Context.locationPrefsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "location_prefs"
)

fun LocationPrefs.Companion.fromContext(context: Context): LocationPrefs {
    return DataStoreLocationPrefs(context.applicationContext.locationPrefsDataStore)
}
