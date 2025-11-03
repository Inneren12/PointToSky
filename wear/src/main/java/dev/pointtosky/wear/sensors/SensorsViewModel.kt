package dev.pointtosky.wear.sensors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pointtosky.wear.sensors.orientation.OrientationFrame
import dev.pointtosky.wear.sensors.orientation.OrientationRepository
import dev.pointtosky.wear.sensors.orientation.OrientationZero
import dev.pointtosky.wear.sensors.orientation.ScreenRotation
import dev.pointtosky.wear.settings.SensorsSettingsDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SensorsViewModel(
    private val orientationRepository: OrientationRepository,
    private val settingsDataStore: SensorsSettingsDataStore,
) : ViewModel() {

    private val _frame = MutableStateFlow<OrientationFrame?>(null)
    val frame: StateFlow<OrientationFrame?> = _frame.asStateFlow()

    private val _zero = MutableStateFlow(OrientationZero())
    val zero: StateFlow<OrientationZero> = _zero.asStateFlow()

    private val _screenRotation = MutableStateFlow(ScreenRotation.ROT_0)
    val screenRotation: StateFlow<ScreenRotation> = _screenRotation.asStateFlow()

    init {
        orientationRepository.start()

        viewModelScope.launch {
            orientationRepository.frames.collect { frame ->
                _frame.value = frame
            }
        }
        viewModelScope.launch {
            orientationRepository.zero.collect { zero ->
                _zero.value = zero
            }
        }
        viewModelScope.launch {
            settingsDataStore.screenRotation.collect { rotation ->
                _screenRotation.value = rotation
                orientationRepository.setRemap(rotation)
            }
        }
    }

    fun selectScreenRotation(screenRotation: ScreenRotation) {
        viewModelScope.launch {
            settingsDataStore.setScreenRotation(screenRotation)
        }
    }

    fun setZeroAzimuthOffset(calibratedDeg: Float) {
        if (calibratedDeg.isNaN() || calibratedDeg.isInfinite()) return
        val currentOffset = _zero.value.azimuthOffsetDeg
        val newOffset = normalizeDeg(currentOffset - calibratedDeg)
        orientationRepository.setZeroAzimuthOffset(newOffset)
    }

    fun resetZero() {
        orientationRepository.resetZero()
    }

    override fun onCleared() {
        orientationRepository.stop()
        super.onCleared()
    }
}

private fun normalizeDeg(d: Float): Float {
    return ((d % 360f) + 360f) % 360f
}
