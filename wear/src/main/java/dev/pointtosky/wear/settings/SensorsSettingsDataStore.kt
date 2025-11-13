package dev.pointtosky.wear.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.pointtosky.core.logging.FrameTraceMode
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.wear.sensors.orientation.ScreenRotation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val DATA_STORE_NAME = "sensors_settings"
private val Context.sensorsSettingsDataStore by preferencesDataStore(name = DATA_STORE_NAME)

class SensorsSettingsDataStore(
    private val context: Context,
) {
    private val screenRotationKey = intPreferencesKey("screen_rotation_degrees")
    private val frameTraceModeKey = stringPreferencesKey("frame_trace_mode")

    val screenRotation: Flow<ScreenRotation> =
        context.sensorsSettingsDataStore.data
            .map { preferences ->
                val storedValue = preferences[screenRotationKey]
                storedValue?.let(ScreenRotation::fromDegrees) ?: ScreenRotation.ROT_0
            }

    val frameTraceMode: Flow<FrameTraceMode> =
        context.sensorsSettingsDataStore.data
            .map { preferences ->
                val storedValue = preferences[frameTraceModeKey]
                storedValue?.let { runCatching { FrameTraceMode.valueOf(it) }.getOrNull() }
                    ?: LogBus.frameTraceMode().value
            }

    suspend fun setScreenRotation(screenRotation: ScreenRotation) {
        context.sensorsSettingsDataStore.edit { preferences ->
            preferences[screenRotationKey] = screenRotation.degrees
        }
    }

    suspend fun setFrameTraceMode(mode: FrameTraceMode) {
        context.sensorsSettingsDataStore.edit { preferences ->
            preferences[frameTraceModeKey] = mode.name
        }
    }
}
