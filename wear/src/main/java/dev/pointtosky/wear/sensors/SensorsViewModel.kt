package dev.pointtosky.wear.sensors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pointtosky.wear.sensors.util.FrameRateAverager
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

    private val frameRateAverager = FrameRateAverager()

    private val _frame = MutableStateFlow<OrientationFrame?>(null)
    val frame: StateFlow<OrientationFrame?> = _frame.asStateFlow()

    private val _frameRate = MutableStateFlow<Float?>(null)
    val frameRate: StateFlow<Float?> = _frameRate.asStateFlow()

    private val _zero = MutableStateFlow(OrientationZero())
    val zero: StateFlow<OrientationZero> = _zero.asStateFlow()

    private val _screenRotation = MutableStateFlow(ScreenRotation.ROT_0)
    val screenRotation: StateFlow<ScreenRotation> = _screenRotation.asStateFlow()

    val isSensorActive: StateFlow<Boolean> = orientationRepository.isActive

    init {
        viewModelScope.launch {
            orientationRepository.frames.collect { frame ->
                _frame.value = frame
                _frameRate.value = frameRateAverager.addSample(frame.timestampNanos)
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
        viewModelScope.launch {
            orientationRepository.isActive.collect { active ->
                if (!active) {
                    frameRateAverager.reset()
                    _frameRate.value = null
                }
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
}

private fun normalizeDeg(d: Float): Float {
    return ((d % 360f) + 360f) % 360f
}
