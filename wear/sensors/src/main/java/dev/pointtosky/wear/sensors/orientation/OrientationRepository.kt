package dev.pointtosky.wear.sensors.orientation

import android.content.Context
import android.hardware.SensorManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface OrientationRepository {
    val frames: Flow<OrientationFrame>
    val zero: StateFlow<OrientationZero>
    val isActive: StateFlow<Boolean>

    fun start(config: OrientationRepositoryConfig = OrientationRepositoryConfig())

    fun stop()

    fun updateZero(orientationZero: OrientationZero)

    fun setZeroAzimuthOffset(offsetDeg: Float) {
        updateZero(OrientationZero(azimuthOffsetDeg = offsetDeg))
    }

    fun setRemap(screenRotation: ScreenRotation) = Unit

    fun resetZero() {
        updateZero(OrientationZero())
    }

    companion object {
        fun create(context: Context): OrientationRepository {
            val appContext = context.applicationContext
            val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
            return RotationVectorOrientationRepository(sensorManager = sensorManager)
        }
    }
}
