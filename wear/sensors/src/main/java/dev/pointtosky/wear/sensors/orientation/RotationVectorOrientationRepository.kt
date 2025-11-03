package dev.pointtosky.wear.sensors.orientation

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class RotationVectorOrientationRepository(
    private val sensorManager: SensorManager,
    private val config: OrientationRepositoryConfig = OrientationRepositoryConfig(),
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : OrientationRepository {
    private val _zero = MutableStateFlow(OrientationZero())
    override val zero: StateFlow<OrientationZero> = _zero.asStateFlow()

    private val framesSharedFlow = MutableSharedFlow<OrientationFrame>(replay = 1, extraBufferCapacity = 1)
    override val frames: SharedFlow<OrientationFrame> = framesSharedFlow.asSharedFlow()

    private var collectionJob: Job? = null

    override fun start() {
        if (collectionJob?.isActive == true) {
            return
        }

        collectionJob = externalScope.launch {
            rotationVectorFrames().collect { frame ->
                framesSharedFlow.emit(frame)
            }
        }.also { job ->
            job.invokeOnCompletion {
                collectionJob = null
            }
        }
    }

    override fun stop() {
        collectionJob?.cancel()
    }

    override fun updateZero(orientationZero: OrientationZero) {
        _zero.value = orientationZero
    }

    private fun rotationVectorFrames(): Flow<OrientationFrame> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            close(IllegalStateException("Rotation vector sensor not available"))
            return@callbackFlow
        }

        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val frameThrottleNanos = config.frameThrottleMs * NANOS_IN_MILLI
        var lastEmitTimestampNs = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!shouldEmit(event.timestamp, lastEmitTimestampNs, frameThrottleNanos)) {
                    return
                }
                lastEmitTimestampNs = event.timestamp

                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val activeMatrix = applyRemap(rotationMatrix, remappedMatrix, config.screenRotation)
                SensorManager.getOrientation(activeMatrix, orientation)

                val rawAzimuth = orientation[0].toDegrees()
                val rawPitch = orientation[1].toDegrees()
                val rawRoll = orientation[2].toDegrees()

                val zeroOffset = zero.value
                val azimuth = applyZeroOffset(rawAzimuth, zeroOffset)
                val pitch = normalizePitchDeg(rawPitch)
                val roll = normalizeRollDeg(rawRoll)

                val forward = extractForwardVector(activeMatrix)
                val normalizedForward = normalizeVector(forward)
                val accuracy = mapAccuracy(event.accuracy)

                trySend(
                    OrientationFrame(
                        timestampNanos = event.timestamp,
                        azimuthDeg = azimuth,
                        pitchDeg = pitch,
                        rollDeg = roll,
                        forward = normalizedForward,
                        accuracy = accuracy,
                    ),
                )
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        val registered = sensorManager.registerListener(
            listener,
            sensor,
            config.samplingPeriodUs,
            0,
        )

        if (!registered) {
            close(IllegalStateException("Failed to register rotation vector listener"))
            return@callbackFlow
        }

        awaitClose {
            sensorManager.unregisterListener(listener)
        }
    }

    private fun shouldEmit(current: Long, previous: Long, throttleNs: Long): Boolean {
        if (throttleNs <= 0L) return true
        if (previous == 0L) return true
        return current - previous >= throttleNs
    }
}

internal fun applyZeroOffset(azimuthDeg: Float, zero: OrientationZero): Float {
    return normalizeAzimuthDeg(azimuthDeg + zero.azimuthOffsetDeg)
}

internal fun normalizeAzimuthDeg(value: Float): Float {
    var result = value % 360f
    if (result < 0f) {
        result += 360f
    }
    if (result >= 360f) {
        result -= 360f
    }
    return result
}

internal fun normalizePitchDeg(value: Float): Float = value.coerceIn(-90f, 90f)

internal fun normalizeRollDeg(value: Float): Float {
    var result = value % 360f
    if (result < -180f) {
        result += 360f
    }
    if (result >= 180f) {
        result -= 360f
    }
    return result
}

internal fun extractForwardVector(rotationMatrix: FloatArray): FloatArray {
    return floatArrayOf(rotationMatrix[2], rotationMatrix[5], rotationMatrix[8])
}

internal fun normalizeVector(vector: FloatArray): FloatArray {
    val length = sqrt(vector.fold(0f) { acc, value -> acc + value * value })
    if (length == 0f) {
        return floatArrayOf(0f, 0f, 0f)
    }
    return floatArrayOf(vector[0] / length, vector[1] / length, vector[2] / length)
}

internal fun mapAccuracy(accuracy: Int): OrientationAccuracy = when (accuracy) {
    SensorManager.SENSOR_STATUS_UNRELIABLE -> OrientationAccuracy.UNRELIABLE
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> OrientationAccuracy.LOW
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> OrientationAccuracy.MEDIUM
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> OrientationAccuracy.HIGH
    else -> OrientationAccuracy.MEDIUM
}

private fun applyRemap(
    sourceMatrix: FloatArray,
    tempMatrix: FloatArray,
    screenRotation: ScreenRotation,
): FloatArray {
    val success = SensorManager.remapCoordinateSystem(
        sourceMatrix,
        screenRotation.remapAxisX,
        screenRotation.remapAxisY,
        tempMatrix,
    )
    return if (success) tempMatrix else sourceMatrix
}

private fun Float.toDegrees(): Float = Math.toDegrees(this.toDouble()).toFloat()

private const val NANOS_IN_MILLI = 1_000_000L
