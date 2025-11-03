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

class AccelMagOrientationRepository(
    private val sensorManager: SensorManager,
    private val config: OrientationRepositoryConfig = OrientationRepositoryConfig(),
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : OrientationRepository {

    @Volatile
    private var screenRotation: ScreenRotation = config.screenRotation

    private val _zero = MutableStateFlow(OrientationZero())
    override val zero: StateFlow<OrientationZero> = _zero.asStateFlow()

    private val framesSharedFlow = MutableSharedFlow<OrientationFrame>(replay = 1, extraBufferCapacity = 1)
    override val frames: SharedFlow<OrientationFrame> = framesSharedFlow.asSharedFlow()

    private val _fps = MutableStateFlow<Float?>(null)
    override val fps: StateFlow<Float?> = _fps.asStateFlow()

    override val source: OrientationSource = OrientationSource.ACCEL_MAG

    private val frameLogger = OrientationFrameLogger(
        source = source,
        scope = externalScope,
        frameTraceMode = LogBus.frameTraceMode(),
        onFpsChanged = { value -> _fps.value = value },
    )

    private var collectionJob: Job? = null
    private var lastAccuracy: OrientationAccuracy? = null

    override fun start() {
        if (collectionJob?.isActive == true) {
            return
        }

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
            accelMagFrames().collect { frame ->
                framesSharedFlow.emit(frame)
            }
        }.also { job ->
            job.invokeOnCompletion { collectionJob = null }
        }
    }

    override fun stop() {
        collectionJob?.cancel()
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

    private fun accelMagFrames(): Flow<OrientationFrame> = callbackFlow {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val magnet = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        if (accel == null || magnet == null) {
            close(IllegalStateException("Accelerometer or magnetometer not available"))
            return@callbackFlow
        }

        val gravity = FloatArray(3)
        val geomagnetic = FloatArray(3)
        val rotationMatrix = FloatArray(9)
        val remappedMatrix = FloatArray(9)
        val orientation = FloatArray(3)
        val frameThrottleNanos = config.frameThrottleMs * NANOS_IN_MILLI
        var lastEmitTimestampNs = 0L
        var accelSet = false
        var magnetSet = false
        var accelAccuracyStatus = SensorManager.SENSOR_STATUS_NO_CONTACT
        var magnetAccuracyStatus = SensorManager.SENSOR_STATUS_NO_CONTACT

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor?.type) {
                    Sensor.TYPE_ACCELEROMETER -> {
                        System.arraycopy(event.values, 0, gravity, 0, gravity.size)
                        accelSet = true
                        accelAccuracyStatus = event.accuracy
                    }
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        System.arraycopy(event.values, 0, geomagnetic, 0, geomagnetic.size)
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
                val combinedAccuracy = mapAccuracy(minOf(accelAccuracyStatus, magnetAccuracyStatus))

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
                when (sensor?.type) {
                    Sensor.TYPE_ACCELEROMETER -> accelAccuracyStatus = accuracy
                    Sensor.TYPE_MAGNETIC_FIELD -> magnetAccuracyStatus = accuracy
                }
                val combinedAccuracy = mapAccuracy(minOf(accelAccuracyStatus, magnetAccuracyStatus))
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
            sensorManager.registerListener(listener, accel, config.samplingPeriodUs, 0) &&
                sensorManager.registerListener(listener, magnet, config.samplingPeriodUs, 0)
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
