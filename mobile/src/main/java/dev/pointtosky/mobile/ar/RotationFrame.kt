package dev.pointtosky.mobile.ar

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import kotlin.math.sqrt

data class RotationFrame(
    val rotationMatrix: FloatArray,
    val forwardWorld: FloatArray,
    val timestampNanos: Long,
)

@Composable
fun rememberRotationFrame(): RotationFrame? {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService<SensorManager>() }
    var frame by remember { mutableStateOf<RotationFrame?>(null) }

    DisposableEffect(sensorManager) {
        if (sensorManager == null) {
            frame = null
            return@DisposableEffect onDispose { }
        }

        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            frame = null
            return@DisposableEffect onDispose { }
        }

        val rotationMatrix = FloatArray(9)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val worldForward = floatArrayOf(
                    -rotationMatrix[2],
                    -rotationMatrix[5],
                    -rotationMatrix[8],
                )
                val normalizedForward = normalizeVector(worldForward)
                frame = RotationFrame(
                    rotationMatrix = rotationMatrix.copyOf(),
                    forwardWorld = normalizedForward,
                    timestampNanos = event.timestamp,
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = sensorManager.registerListener(
            listener,
            sensor,
            SensorManager.SENSOR_DELAY_GAME,
        )

        onDispose {
            if (registered) {
                sensorManager.unregisterListener(listener)
            }
        }
    }

    return frame
}

private fun normalizeVector(vector: FloatArray): FloatArray {
    val length = sqrt(vector.fold(0f) { acc, value -> acc + value * value })
    if (length == 0f) {
        return floatArrayOf(0f, 0f, -1f)
    }
    return floatArrayOf(vector[0] / length, vector[1] / length, vector[2] / length)
}
