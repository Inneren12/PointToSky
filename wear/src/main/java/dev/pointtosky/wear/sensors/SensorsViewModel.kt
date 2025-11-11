package dev.pointtosky.wear.sensors

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.pointtosky.core.logging.FrameTraceMode
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.logging.LogWriterStats
import dev.pointtosky.wear.sensors.orientation.OrientationFrame
import dev.pointtosky.wear.sensors.orientation.OrientationRepository
import dev.pointtosky.wear.sensors.orientation.OrientationSource
import dev.pointtosky.wear.sensors.orientation.OrientationZero
import dev.pointtosky.wear.sensors.orientation.ScreenRotation
import dev.pointtosky.wear.settings.SensorsSettingsDataStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SensorsViewModel(
    private val orientationRepository: OrientationRepository,
    private val settingsDataStore: SensorsSettingsDataStore,
) : ViewModel() {

    val frames: Flow<OrientationFrame> = orientationRepository.frames
    val zero: StateFlow<OrientationZero> = orientationRepository.zero
    val fps: StateFlow<Float?> = orientationRepository.fps

    private val _frameTraceMode = MutableStateFlow(LogBus.frameTraceMode().value)
    val frameTraceMode: StateFlow<FrameTraceMode> = _frameTraceMode.asStateFlow()

    private val _writerStats = MutableStateFlow(LogWriterStats())
    val writerStats: StateFlow<LogWriterStats> = _writerStats.asStateFlow()

    val source: StateFlow<OrientationSource> = orientationRepository.activeSource

    val isSensorActive: StateFlow<Boolean> = orientationRepository.isRunning

    private val _screenRotation = MutableStateFlow(ScreenRotation.ROT_0)
    val screenRotation: StateFlow<ScreenRotation> = _screenRotation.asStateFlow()

    init {
        viewModelScope.launch {
            settingsDataStore.screenRotation.collect { rotation ->
                _screenRotation.value = rotation
                orientationRepository.setRemap(rotation)
            }
        }
        viewModelScope.launch {
            settingsDataStore.frameTraceMode.collect { mode ->
                _frameTraceMode.value = mode
                LogBus.setFrameTraceMode(mode)
            }
        }
        viewModelScope.launch {
            while (isActive) {
                _writerStats.value = LogBus.writerStats()
                delay(1_000)
            }
        }
    }

    fun selectScreenRotation(screenRotation: ScreenRotation) {
        viewModelScope.launch {
            settingsDataStore.setScreenRotation(screenRotation)
        }
    }

    fun selectFrameTraceMode(mode: FrameTraceMode) {
        viewModelScope.launch {
            settingsDataStore.setFrameTraceMode(mode)
            LogBus.setFrameTraceMode(mode)
        }
    }

    fun setZeroAzimuthOffset(calibratedDeg: Float) {
        if (calibratedDeg.isNaN() || calibratedDeg.isInfinite()) return
        val currentOffset = zero.value.azimuthOffsetDeg
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
