package dev.pointtosky.mobile.ar

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.view.Surface
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.getSystemService
import kotlin.math.cos
import kotlin.math.sin
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

        val listener =
            object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    val displayRotation =
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) context.display else null)?.rotation
                            ?: context.getSystemService<WindowManager>()?.defaultDisplay?.rotation
                            ?: Surface.ROTATION_0
                    // Normalize rotation so device axes follow the current display orientation.
                    val remapped = remapForDisplay(rotationMatrix, displayRotation, rotationMatrix)
                    val worldForward =
                        floatArrayOf(
                            -remapped[2],
                            -remapped[5],
                            -remapped[8],
                        )
                    val normalizedForward = normalizeVector(worldForward)
                    frame =
                        RotationFrame(
                            rotationMatrix = remapped.copyOf(),
                            forwardWorld = normalizedForward,
                            timestampNanos = event.timestamp,
                        )
                }

                override fun onAccuracyChanged(
                    sensor: Sensor?,
                    accuracy: Int,
                ) = Unit
            }

        val registered =
            sensorManager.registerListener(
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

/**
 * Rotates this frame's world reference about the up axis by [declinationDeg], converting the Android
 * sensor world frame (Y = MAGNETIC north) into a TRUE-north world frame. After this,
 * vectorToHorizontal(forwardWorld) is TRUE azimuth and transpose(rotationMatrix) maps TRUE-north world
 * vectors to device space. Mirrors the watch's Horizontal.toTrueNorth(declination).
 */
internal fun RotationFrame.correctedForTrueNorth(declinationDeg: Double): RotationFrame {
    if (declinationDeg == 0.0) return this
    val d = Math.toRadians(declinationDeg)
    val c = cos(d).toFloat()
    val s = sin(d).toFloat()
    val r = rotationMatrix
    val cm = floatArrayOf(
        c * r[0] + s * r[3], c * r[1] + s * r[4], c * r[2] + s * r[5],
        -s * r[0] + c * r[3], -s * r[1] + c * r[4], -s * r[2] + c * r[5],
        r[6], r[7], r[8],
    )
    val f = forwardWorld
    val cf = floatArrayOf(
        c * f[0] + s * f[1],
        -s * f[0] + c * f[1],
        f[2],
    )
    return copy(rotationMatrix = cm, forwardWorld = cf)
}

private fun normalizeVector(vector: FloatArray): FloatArray {
    val length = sqrt(vector.fold(0f) { acc, value -> acc + value * value })
    if (length == 0f) {
        return floatArrayOf(0f, 0f, -1f)
    }
    return floatArrayOf(vector[0] / length, vector[1] / length, vector[2] / length)
}
