package dev.pointtosky.wear.sensors.orientation

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import dev.pointtosky.core.logging.LogBus
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
import kotlin.jvm.Volatile
import kotlin.math.sqrt

class RotationVectorOrientationRepository(
    private val sensorManager: SensorManager,
    initialConfig: OrientationRepositoryConfig = OrientationRepositoryConfig(),
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : OrientationRepository {
    @Volatile
    private var screenRotation: ScreenRotation = initialConfig.screenRotation

    @Volatile
    private var config: OrientationRepositoryConfig = initialConfig

    private val _zero = MutableStateFlow(OrientationZero())
    override val zero: StateFlow<OrientationZero> = _zero.asStateFlow()

    private val framesSharedFlow = MutableSharedFlow<OrientationFrame>(replay = 1, extraBufferCapacity = 1)
    override val frames: SharedFlow<OrientationFrame> = framesSharedFlow.asSharedFlow()

    private val _fps = MutableStateFlow<Float?>(null)
    override val fps: StateFlow<Float?> = _fps.asStateFlow()

    override val source: OrientationSource = OrientationSource.ROTATION_VECTOR
    private val _activeSource = MutableStateFlow(source)
    override val activeSource: StateFlow<OrientationSource> = _activeSource.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val frameLogger = OrientationFrameLogger(
        source = source,
        scope = externalScope,
        frameTraceMode = LogBus.frameTraceMode(),
        onFpsChanged = { value -> _fps.value = value },
    )

    private var lastAccuracy: OrientationAccuracy? = null

    private var collectionJob: Job? = null

    override fun start(config: OrientationRepositoryConfig) {
        if (collectionJob?.isActive == true && this.config == config) {
            return
        }

        if (collectionJob?.isActive == true) {
            stop()
        }

        this.config = config
        screenRotation = config.screenRotation

        LogBus.i(
            tag = "Sensors",
            msg = "start",
            payload = mapOf(
                "samplingUs" to config.samplingPeriodUs,
                "throttleMs" to config.frameThrottleMs,
                "source" to source.name,
            ),
        )

        collectionJob = externalScope.launch {
            rotationVectorFrames(config).collect { frame ->
                framesSharedFlow.emit(frame)
            }
        }.also { job ->
            job.invokeOnCompletion {
                collectionJob = null
                _isRunning.value = false
            }
        }
        _isRunning.value = true
    }

    override fun stop() {
        collectionJob?.cancel()
        frameLogger.reset()
        _isRunning.value = false
        LogBus.i(
            tag = "Sensors",
            msg = "stop",
            payload = mapOf("source" to source.name),
        )
    }

    override fun updateZero(orientationZero: OrientationZero) {
        _zero.value = orientationZero
    }

    override fun setZeroAzimuthOffset(offsetDeg: Float) {
        super.setZeroAzimuthOffset(offsetDeg)
        LogBus.i(
            tag = "Sensors",
            msg = "calibration",
            payload = mapOf(
                "zeroDeg" to _zero.value.azimuthOffsetDeg,
                "remap" to screenRotation.name,
                "source" to source.name,
            ),
        )
    }

    override fun setRemap(screenRotation: ScreenRotation) {
        this.screenRotation = screenRotation
        LogBus.i(
            tag = "Sensors",
            msg = "calibration",
            payload = mapOf(
                "zeroDeg" to _zero.value.azimuthOffsetDeg,
                "remap" to screenRotation.name,
                "axisX" to axisLabel(screenRotation.remapAxisX),
                "axisY" to axisLabel(screenRotation.remapAxisY),
                "source" to source.name,
            ),
        )
    }

    private fun rotationVectorFrames(activeConfig: OrientationRepositoryConfig): Flow<OrientationFrame> = callbackFlow {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (sensor == null) {
            close(IllegalStateException("Rotation vector sensor not available"))
            return@callbackFlow
        }

        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val frameThrottleNanos = activeConfig.frameThrottleMs * NANOS_IN_MILLI
        var lastEmitTimestampNs = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (!shouldEmit(event.timestamp, lastEmitTimestampNs, frameThrottleNanos)) {
                    return
                }
                lastEmitTimestampNs = event.timestamp

                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                val activeMatrix = applyRemap(rotationMatrix, remappedMatrix, screenRotation)
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
                val frame = OrientationFrame(
                    timestampNanos = event.timestamp,
                    azimuthDeg = azimuth,
                    pitchDeg = pitch,
                    rollDeg = roll,
                    forward = normalizedForward,
                    accuracy = accuracy,
                    rotationMatrix = activeMatrix.copyOf(),
                )
                frameLogger.submit(frame)
                trySend(frame)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                val mapped = mapAccuracy(accuracy)
                if (mapped != lastAccuracy) {
                    lastAccuracy = mapped
                    LogBus.d(
                        tag = "Sensors",
                        msg = "accuracy",
                        payload = mapOf(
                            "level" to mapped,
                            "source" to source.name,
                        ),
                    )
                }
            }
        }

        val registered = runCatching {
            sensorManager.registerListener(
                listener,
                sensor,
                activeConfig.samplingPeriodUs,
                0,
            )
        }.onFailure { throwable ->
            LogBus.e(
                tag = "Sensors",
                msg = "error",
                err = throwable,
                payload = mapOf(
                    "operation" to "register",
                    "source" to source.name,
                    "sensor" to "ROTATION_VECTOR",
                ),
            )
        }.getOrDefault(false)

        if (!registered) {
            LogBus.e(
                tag = "Sensors",
                msg = "error",
                payload = mapOf(
                    "operation" to "register",
                    "source" to source.name,
                    "sensor" to "ROTATION_VECTOR",
                ),
            )
            close(IllegalStateException("Failed to register rotation vector listener"))
            return@callbackFlow
        }

        awaitClose {
            runCatching { sensorManager.unregisterListener(listener) }
                .onFailure { throwable ->
                    LogBus.e(
                        tag = "Sensors",
                        msg = "error",
                        err = throwable,
                        payload = mapOf(
                            "operation" to "unregister",
                            "source" to source.name,
                            "sensor" to "ROTATION_VECTOR",
                        ),
                    )
                }
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
