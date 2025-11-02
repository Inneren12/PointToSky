package dev.pointtosky.wear.sensors

import dev.pointtosky.wear.sensors.model.OrientationFrame
import dev.pointtosky.wear.sensors.model.SensorConfig
import kotlinx.coroutines.flow.Flow

/**
 * Entry point for observing device orientation updates.
 */
interface OrientationRepository {
    val frames: Flow<OrientationFrame>

    suspend fun start(config: SensorConfig = SensorConfig())

    suspend fun stop()

    fun setZeroAzimuthOffset(deg: Float)

    fun setRemap(rotation: ScreenRotation)
}
