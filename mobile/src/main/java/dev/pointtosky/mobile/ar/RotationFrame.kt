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
import dev.pointtosky.core.astro.projection.camera.TimedRotationSample
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

private const val ZENITH_UP_EPS = 0.05f // ~3° from straight up/down; below this, screen-up of the world is ill-defined

data class RotationFrame(
    val rotationMatrix: FloatArray,
    val forwardWorld: FloatArray,
    val timestampNanos: Long,
)

/**
 * [onRotationSample] is called with a [TimedRotationSample] built from the same `event.timestamp`
 * and display-remapped rotation matrix as the [RotationFrame] produced by this call, immediately
 * after each new sensor sample (CAM-1d) — a seam for feeding recent production rotation samples into
 * `RotationSampleHistory` without duplicating the sensor pipeline. Defaults to a no-op so existing
 * callers are unaffected.
 */
@Composable
fun rememberRotationFrame(onRotationSample: (TimedRotationSample) -> Unit = {}): RotationFrame? {
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
                    val newFrame =
                        RotationFrame(
                            rotationMatrix = remapped.copyOf(),
                            forwardWorld = normalizedForward,
                            timestampNanos = event.timestamp,
                        )
                    // Feed the timestamp history before publishing the Compose state, so a recomposition
                    // triggered by `frame = newFrame` can never observe a camera-frame pairing seam that
                    // is missing the rotation sample for this very RotationFrame.
                    onRotationSample(
                        TimedRotationSample(
                            timestampNanos = newFrame.timestampNanos,
                            rotationMatrix = newFrame.rotationMatrix,
                        ),
                    )
                    frame = newFrame
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
 * In the project convention az = atan2(x, y), this maps magnetic azimuth m to true azimuth m + declinationDeg.
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

/**
 * Screen-space roll of the device about its view axis, in degrees, in Compose's clockwise-positive
 * rotationZ convention: the angle by which a label must be rotated so its "up" points at the world
 * zenith as projected onto the screen. Returns [fallback] when the device points within ~ZENITH_UP_EPS
 * of straight up/down (projection ill-defined, would otherwise jitter).
 *
 * world-up in device coords = 3rd row of the device→world matrix = (m[6], m[7], m[8]); its screen
 * projection is (m[6], m[7]); the clockwise angle to screen-up is atan2(m[6], m[7]). Valid because the
 * screen is portrait-locked (display ROTATION_0), so the matrix axes are the screen axes. Independent of
 * the declination correction (that left-multiplies Rz, which does not change row 2).
 *
 * Sign verified on three poses: upright → 0°; rolled 90° clockwise → −90°; rolled 90° counter-clockwise → +90°.
 * Confirm on-device regardless; if labels rotate the wrong way, negate the result.
 */
internal fun deviceRollDegrees(rotationMatrix: FloatArray, fallback: Float = 0f): Float {
    val x = rotationMatrix[6]
    val y = rotationMatrix[7]
    if (hypot(x, y) < ZENITH_UP_EPS) return fallback
    return Math.toDegrees(atan2(x, y).toDouble()).toFloat()
}

private fun normalizeVector(vector: FloatArray): FloatArray {
    val length = sqrt(vector.fold(0f) { acc, value -> acc + value * value })
    if (length == 0f) {
        return floatArrayOf(0f, 0f, -1f)
    }
    return floatArrayOf(vector[0] / length, vector[1] / length, vector[2] / length)
}
