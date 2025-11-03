package dev.pointtosky.wear.sensors

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import dev.pointtosky.wear.sensors.orientation.OrientationRepository
import dev.pointtosky.wear.settings.SensorsSettingsDataStore

class SensorsViewModelFactory(
    private val orientationRepository: OrientationRepository,
    private val appContext: Context,
) : ViewModelProvider.Factory {

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SensorsViewModel::class.java)) {
            val settingsDataStore = SensorsSettingsDataStore(appContext)
            @Suppress("UNCHECKED_CAST")
            return SensorsViewModel(orientationRepository, settingsDataStore) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class ${modelClass.name}")
    }
}
