package dev.pointtosky.wear.sensors

import dev.pointtosky.wear.sensors.model.OrientationFrame
import dev.pointtosky.wear.sensors.model.SensorConfig
import kotlinx.coroutines.flow.Flow

/** Provides a stream of [OrientationFrame] updates from the device sensors. */
interface OrientationRepository {
    val frames: Flow<OrientationFrame>

    suspend fun start(config: SensorConfig = SensorConfig())

    suspend fun stop()

    /** Applies a zero-offset for azimuth measurements to support calibration. */
    fun setZeroAzimuthOffset(deg: Float)

    /** Remaps the sensor output to match the current screen orientation. */
    fun setRemap(rotation: ScreenRotation)
}
