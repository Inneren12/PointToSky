package dev.pointtosky.wear.sensors.repository

import dev.pointtosky.wear.sensors.model.OrientationFrame
import dev.pointtosky.wear.sensors.model.ScreenRotation
import dev.pointtosky.wear.sensors.model.SensorConfig
import kotlinx.coroutines.flow.Flow

/**
 * Provides a flow of orientation frames computed from device sensors.
 */
interface OrientationRepository {
    val frames: Flow<OrientationFrame>

    suspend fun start(config: SensorConfig = SensorConfig())

    suspend fun stop()

    fun setZeroAzimuthOffset(deg: Float)

    fun setRemap(rotation: ScreenRotation)
}
