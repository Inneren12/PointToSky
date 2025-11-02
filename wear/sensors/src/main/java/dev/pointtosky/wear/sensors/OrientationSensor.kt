package dev.pointtosky.wear.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Provides a cold [Flow] of rotation vector readings from the wearable device.
 */
class OrientationSensor(
    private val sensorManager: SensorManager,
) {
    /**
     * Emits a clone of the latest [Sensor.TYPE_ROTATION_VECTOR] values whenever they change.
     */
    fun rotationVector(): Flow<FloatArray> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            close(IllegalStateException("Rotation vector sensor not available"))
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(event.values.clone())
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_UI,
        )

        if (!registered) {
            close(IllegalStateException("Failed to register rotation vector listener"))
            return@callbackFlow
        }

        awaitClose { sensorManager.unregisterListener(listener) }
    }.distinctUntilChanged(FloatArray::contentEquals)
}
