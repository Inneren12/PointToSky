package dev.pointtosky.wear.sensors.fake

import android.os.SystemClock
import dev.pointtosky.wear.sensors.model.OrientationFrame
import dev.pointtosky.wear.sensors.model.ScreenRotation
import dev.pointtosky.wear.sensors.model.SensorAccuracy
import dev.pointtosky.wear.sensors.model.SensorConfig
import dev.pointtosky.wear.sensors.repository.OrientationRepository
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.yield

/**
 * Fake orientation provider that emits a smooth sinusoidal sequence of frames.
 */
class FakeOrientationRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : OrientationRepository {

    private val framesFlow = MutableSharedFlow<OrientationFrame>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val jobMutex = Mutex()
    private var job: Job? = null
    private val zeroAzimuthOffset = AtomicReference(0f)
    private val screenRotation = AtomicReference(ScreenRotation.ROT_0)

    override val frames: SharedFlow<OrientationFrame> = framesFlow.asSharedFlow()

    override suspend fun start(config: SensorConfig) {
        jobMutex.withLock {
            if (job?.isActive == true) return
            job = scope.launch {
                val startTimestamp = SystemClock.elapsedRealtime()
                while (isActive) {
                    val elapsedMs = SystemClock.elapsedRealtime() - startTimestamp
                    val tSeconds = elapsedMs / 1000f

                    val baseAzimuth = (tSeconds * 45f) % 360f
                    val pitch = (sin(tSeconds.toDouble()) * 30.0).toFloat().coerceIn(-90f, 90f)
                    val roll = (cos(tSeconds.toDouble() * 0.5) * 90.0).toFloat().coerceIn(-180f, 180f)

                    val azimuth = normalizeDegrees(
                        baseAzimuth + zeroAzimuthOffset.get() + screenRotation.get().toDegrees()
                    )
                    val azimuthRad = Math.toRadians(azimuth.toDouble())
                    val pitchRad = Math.toRadians(pitch.toDouble())

                    val forward = floatArrayOf(
                        (cos(pitchRad) * sin(azimuthRad)).toFloat(),
                        (cos(pitchRad) * cos(azimuthRad)).toFloat(),
                        sin(pitchRad).toFloat(),
                    )
                    val rotationMatrix = floatArrayOf(
                        1f, 0f, 0f,
                        0f, 1f, 0f,
                        0f, 0f, 1f,
                    )

                    val frame = OrientationFrame(
                        timestampMs = System.currentTimeMillis(),
                        azimuthDeg = azimuth,
                        pitchDeg = pitch,
                        rollDeg = roll,
                        forward = forward,
                        rotationMatrix = rotationMatrix,
                        accuracy = SensorAccuracy.MEDIUM,
                    )

                    framesFlow.emit(frame)
                    delay(config.frameThrottleMs)
                    yield()
                }
            }
        }
    }

    override suspend fun stop() {
        jobMutex.withLock {
            job?.cancelAndJoin()
            job = null
        }
    }

    override fun setZeroAzimuthOffset(deg: Float) {
        zeroAzimuthOffset.set(deg)
    }

    override fun setRemap(rotation: ScreenRotation) {
        screenRotation.set(rotation)
    }

    private fun normalizeDegrees(value: Float): Float {
        var result = value % 360f
        if (result < 0f) {
            result += 360f
        }
        return result
    }

    private fun ScreenRotation.toDegrees(): Float = when (this) {
        ScreenRotation.ROT_0 -> 0f
        ScreenRotation.ROT_90 -> 90f
        ScreenRotation.ROT_180 -> 180f
        ScreenRotation.ROT_270 -> 270f
    }
}
