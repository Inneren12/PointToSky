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

private const val LOW_PASS_ALPHA = 0.15f // TODO: tune

class AccelMagOrientationRepository(
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

    override val source: OrientationSource = OrientationSource.ACCEL_MAG
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

    private var collectionJob: Job? = null
    private var lastAccuracy: OrientationAccuracy? = null

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
            accelMagFrames(config).collect { frame ->
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
        if (this.screenRotation == screenRotation) return
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

    private fun accelMagFrames(activeConfig: OrientationRepositoryConfig): Flow<OrientationFrame> = callbackFlow {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnet = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (accel == null || magnet == null) {
            close(IllegalStateException("Accelerometer or magnetometer not available"))
            return@callbackFlow
        }

        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        val accelFilter = ExponentialLowPassFilter(alpha = LOW_PASS_ALPHA, dimension = gravity.size)
        val magnetFilter = ExponentialLowPassFilter(alpha = LOW_PASS_ALPHA, dimension = geomagnetic.size)
        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val frameThrottleNanos = activeConfig.frameThrottleMs * NANOS_IN_MILLI
        var lastEmitTimestampNs = 0L
        var accelSet = false
        var magnetSet = false
        var magnetAccuracyStatus = SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor?.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        accelFilter.filter(event.values, gravity)
                        accelSet = true
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        magnetFilter.filter(event.values, geomagnetic)
                        magnetSet = true
                        magnetAccuracyStatus = event.accuracy
                    }
                    else -> return
                }

                if (!accelSet || !magnetSet) return
                if (!shouldEmit(event.timestamp, lastEmitTimestampNs, frameThrottleNanos)) return
                lastEmitTimestampNs = event.timestamp

                val success = SensorManager.getRotationMatrix(rotationMatrix, null, gravity, geomagnetic)
                if (!success) return

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
                val combinedAccuracy = mapAccuracy(magnetAccuracyStatus)

                val frame = OrientationFrame(
                    timestampNanos = event.timestamp,
                    azimuthDeg = azimuth,
                    pitchDeg = pitch,
                    rollDeg = roll,
                    forward = normalizedForward,
                    accuracy = combinedAccuracy,
                    rotationMatrix = activeMatrix.copyOf(),
                )
                frameLogger.submit(frame)
                trySend(frame)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (sensor?.type != Sensor.TYPE_MAGNETIC_FIELD) {
                    return
                }
                magnetAccuracyStatus = accuracy
                val combinedAccuracy = mapAccuracy(magnetAccuracyStatus)
                if (combinedAccuracy != lastAccuracy) {
                    lastAccuracy = combinedAccuracy
                    LogBus.d(
                        tag = "Sensors",
                        msg = "accuracy",
                        payload = mapOf(
                            "level" to combinedAccuracy,
                            "source" to source.name,
                        ),
                    )
                }
            }
        }

        val registered = runCatching {
            val accelRegistered = sensorManager.registerListener(
                listener,
                accel,
                activeConfig.samplingPeriodUs,
                0,
            )
            val magnetRegistered = sensorManager.registerListener(
                listener,
                magnet,
                activeConfig.samplingPeriodUs,
                0,
            )
            accelRegistered && magnetRegistered
        }.onFailure { throwable ->
            LogBus.e(
                tag = "Sensors",
                msg = "error",
                err = throwable,
                payload = mapOf(
                    "operation" to "register",
                    "source" to source.name,
                    "sensor" to "ACCEL_MAG",
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
                    "sensor" to "ACCEL_MAG",
                ),
            )
            close(IllegalStateException("Failed to register accel/mag listeners"))
            return@callbackFlow
        }

        awaitClose {
            runCatching { sensorManager.unregisterListener(listener, accel) }
                .onFailure { throwable ->
                    LogBus.e(
                        tag = "Sensors",
                        msg = "error",
                        err = throwable,
                        payload = mapOf(
                            "operation" to "unregister",
                            "source" to source.name,
                            "sensor" to "ACCEL",
                        ),
                    )
                }
            runCatching { sensorManager.unregisterListener(listener, magnet) }
                .onFailure { throwable ->
                    LogBus.e(
                        tag = "Sensors",
                        msg = "error",
                        err = throwable,
                        payload = mapOf(
                            "operation" to "unregister",
                            "source" to source.name,
                            "sensor" to "MAG",
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
