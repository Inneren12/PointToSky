package dev.pointtosky.mobile.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.datalayer.PATH_SENSOR_HEADING
import dev.pointtosky.core.datalayer.SensorHeadingMessage
import dev.pointtosky.mobile.datalayer.MobileBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class PhoneCompassBridge(
    context: Context,
    private val sensorManager: SensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager,
    private val senderProvider: () -> MobileBridge.Sender = { MobileBridge.get(context.applicationContext) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val elapsedRealtime: () -> Long = SystemClock::elapsedRealtime,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : SensorEventListener {

    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val started = AtomicBoolean(false)
    private val registered = AtomicBoolean(false)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val rotationMatrix = FloatArray(9)
    private val orientation = FloatArray(3)

    private var lastDispatchElapsedMs: Long = 0L

    fun start() {
        if (!started.compareAndSet(false, true)) return
        if (_enabled.value) {
            registerListener()
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        unregisterListener()
    }

    fun setEnabled(enabled: Boolean): Boolean {
        val actual = enabled && rotationSensor != null
        _enabled.value = actual
        if (!started.get()) {
            return actual
        }
        if (actual) {
            registerListener()
        } else {
            unregisterListener()
        }
        return actual
    }

    private fun registerListener() {
        if (registered.get()) return
        val sensor = rotationSensor ?: return
        if (sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)) {
            registered.set(true)
        }
    }

    private fun unregisterListener() {
        if (!registered.get()) return
        sensorManager.unregisterListener(this)
        registered.set(false)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!_enabled.value) return
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
        SensorManager.getOrientation(rotationMatrix, orientation)
        val azimuthRad = orientation[0]
        val azimuthDeg = Math.toDegrees(azimuthRad.toDouble())
        val normalized = normalizeDeg(azimuthDeg)
        val nowElapsed = elapsedRealtime()
        if (nowElapsed - lastDispatchElapsedMs < MIN_DISPATCH_INTERVAL_MS) {
            return
        }
        lastDispatchElapsedMs = nowElapsed
        val timestampMs = clock()
        scope.launch {
            val sender = senderProvider()
            sender.send(PATH_SENSOR_HEADING) { cid ->
                val message = SensorHeadingMessage(
                    cid = cid,
                    azDeg = normalized,
                    ts = timestampMs,
                )
                JsonCodec.encode(message)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        private const val MIN_DISPATCH_INTERVAL_MS: Long = 500L
    }
}

private fun normalizeDeg(value: Double): Double {
    var normalized = value % 360.0
    if (normalized < 0.0) {
        normalized += 360.0
    }
    return normalized
}
