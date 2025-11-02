package dev.pointtosky.wear.sensors

import android.os.SystemClock
import dev.pointtosky.wear.sensors.model.OrientationFrame
import dev.pointtosky.wear.sensors.model.SensorAccuracy
import dev.pointtosky.wear.sensors.model.SensorConfig
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Fake repository that generates a smooth sine-wave orientation curve. Useful for previews/tests.
 */
class FakeOrientationRepository(
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val timeProvider: () -> Long = { SystemClock.elapsedRealtime() }
) : OrientationRepository {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val framesFlow = MutableSharedFlow<OrientationFrame>(replay = 1)

    override val frames: Flow<OrientationFrame> = framesFlow.asSharedFlow()

    @Volatile
    private var zeroAzimuthOffset: Float = 0f

    @Volatile
    private var screenRotation: ScreenRotation = ScreenRotation.ROT_0

    private val jobMutex = Mutex()
    private var streamJob: Job? = null

    override suspend fun start(config: SensorConfig) {
        jobMutex.withLock {
            if (streamJob != null) return
            streamJob = scope.launch {
                val startTime = timeProvider()
                while (isActive) {
                    val now = timeProvider()
                    val elapsedMs = now - startTime
                    val seconds = elapsedMs / 1000.0

                    val baseAzimuth = normalizeDegrees((seconds * 45.0).toFloat())
                    val basePitch = (30f * sin(seconds)).coerceIn(-90f, 90f)
                    val baseRoll = (45f * sin(seconds / 2)).coerceIn(-180f, 180f)

                    val rotationOffset = screenRotation.offsetDegrees()
                    val adjustedAzimuth = normalizeDegrees(baseAzimuth - zeroAzimuthOffset + rotationOffset)

                    val rotationMatrix = buildRotationMatrix(
                        yawDeg = adjustedAzimuth,
                        pitchDeg = basePitch,
                        rollDeg = baseRoll
                    )
                    val forward = forwardVector(rotationMatrix)

                    val frame = OrientationFrame(
                        timestampMs = now,
                        azimuthDeg = adjustedAzimuth,
                        pitchDeg = basePitch,
                        rollDeg = baseRoll,
                        forward = forward,
                        rotationMatrix = rotationMatrix,
                        accuracy = SensorAccuracy.HIGH
                    )
                    framesFlow.emit(frame)

                    val delayMs = max(1L, config.frameThrottleMs)
                    delay(delayMs)
                }
            }
        }
    }

    override suspend fun stop() {
        val job = jobMutex.withLock {
            val current = streamJob ?: return
            streamJob = null
            current
        }
        job.cancelAndJoin()
    }

    override fun setZeroAzimuthOffset(deg: Float) {
        zeroAzimuthOffset = normalizeDegrees(deg)
    }

    override fun setRemap(rotation: ScreenRotation) {
        screenRotation = rotation
    }

    private fun ScreenRotation.offsetDegrees(): Float = when (this) {
        ScreenRotation.ROT_0 -> 0f
        ScreenRotation.ROT_90 -> 90f
        ScreenRotation.ROT_180 -> 180f
        ScreenRotation.ROT_270 -> 270f
    }

    private fun normalizeDegrees(value: Float): Float {
        var result = value % 360f
        if (result < 0f) {
            result += 360f
        }
        return result
    }

    private fun buildRotationMatrix(
        yawDeg: Float,
        pitchDeg: Float,
        rollDeg: Float
    ): FloatArray {
        val yaw = yawDeg.toRadians()
        val pitch = pitchDeg.toRadians()
        val roll = rollDeg.toRadians()

        val rz = rotationZ(yaw)
        val rx = rotationX(pitch)
        val ry = rotationY(roll)

        val combined = multiply(multiply(rz, rx), ry)
        return FloatArray(9) { combined[it].toFloat() }
    }

    private fun forwardVector(rotationMatrix: FloatArray): FloatArray {
        val x = -rotationMatrix[2]
        val y = -rotationMatrix[5]
        val z = -rotationMatrix[8]
        val norm = sqrt(x * x + y * y + z * z)
        if (norm == 0f) {
            return floatArrayOf(0f, 0f, -1f)
        }
        return floatArrayOf(x / norm, y / norm, z / norm)
    }

    private fun Float.toRadians(): Double = this / 180.0 * PI

    private fun rotationZ(angle: Double) = doubleArrayOf(
        cos(angle), -sin(angle), 0.0,
        sin(angle), cos(angle), 0.0,
        0.0, 0.0, 1.0
    )

    private fun rotationX(angle: Double) = doubleArrayOf(
        1.0, 0.0, 0.0,
        0.0, cos(angle), -sin(angle),
        0.0, sin(angle), cos(angle)
    )

    private fun rotationY(angle: Double) = doubleArrayOf(
        cos(angle), 0.0, sin(angle),
        0.0, 1.0, 0.0,
        -sin(angle), 0.0, cos(angle)
    )

    private fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray {
        return doubleArrayOf(
            a[0] * b[0] + a[1] * b[3] + a[2] * b[6],
            a[0] * b[1] + a[1] * b[4] + a[2] * b[7],
            a[0] * b[2] + a[1] * b[5] + a[2] * b[8],
            a[3] * b[0] + a[4] * b[3] + a[5] * b[6],
            a[3] * b[1] + a[4] * b[4] + a[5] * b[7],
            a[3] * b[2] + a[4] * b[5] + a[5] * b[8],
            a[6] * b[0] + a[7] * b[3] + a[8] * b[6],
            a[6] * b[1] + a[7] * b[4] + a[8] * b[7],
            a[6] * b[2] + a[7] * b[5] + a[8] * b[8]
        )
    }
}
