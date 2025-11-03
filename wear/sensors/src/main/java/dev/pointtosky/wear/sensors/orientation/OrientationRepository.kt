package dev.pointtosky.wear.sensors.orientation

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface OrientationRepository {
    val frames: Flow<OrientationFrame>
    val zero: StateFlow<OrientationZero>
    val fps: StateFlow<Float?>
    val source: OrientationSource

    fun start()

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
            val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            return if (rotationSensor != null) {
                RotationVectorOrientationRepository(sensorManager = sensorManager)
            } else {
                AccelMagOrientationRepository(sensorManager = sensorManager)
            }
        }
    }
}
