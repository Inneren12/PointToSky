package dev.pointtosky.wear.sensors.fake

import dev.pointtosky.wear.sensors.OrientationRepository
import dev.pointtosky.wear.sensors.ScreenRotation
import dev.pointtosky.wear.sensors.model.OrientationFrame
import dev.pointtosky.wear.sensors.model.SensorAccuracy
import dev.pointtosky.wear.sensors.model.SensorConfig
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
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

/**
 * Fake implementation of [OrientationRepository] that produces deterministic
 * sinusoidal orientation updates for previews and tests.
 */
class FakeOrientationRepository(
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
) : OrientationRepository {

    private val framesFlow = MutableSharedFlow<OrientationFrame>(replay = 1, extraBufferCapacity = 1)

    override val frames: Flow<OrientationFrame> = framesFlow.asSharedFlow()

    @Volatile
    private var zeroOffsetDeg: Float = 0f

    private val screenRotation = AtomicReference(ScreenRotation.ROT_0)

    @Volatile
    private var latestConfig: SensorConfig = SensorConfig()

    private var generatorJob: Job? = null

    override suspend fun start(config: SensorConfig) {
        latestConfig = config
        if (generatorJob?.isActive == true) return
        generatorJob = scope.launch {
            val startTimeMs = timeProvider()
            while (isActive) {
                val now = timeProvider()
                val elapsedSeconds = (now - startTimeMs) / 1000.0
                val baseAzimuth = (elapsedSeconds * 30.0).toFloat() // ~30°/s rotation
                val pitch = (sin(elapsedSeconds) * 30.0).toFloat() // ±30°
                val roll = (cos(elapsedSeconds * 0.6) * 60.0).toFloat() // ±60°

                val adjustedAzimuth = normalizeDegrees(baseAzimuth - zeroOffsetDeg)
                val (azimuthDeg, pitchDeg, rollDeg) = applyScreenRotation(
                    adjustedAzimuth,
                    pitch,
                    roll,
                    screenRotation.get()
                )

                val forwardVector = computeForwardVector(azimuthDeg, pitchDeg)
                val rotationMatrix = computeRotationMatrix(azimuthDeg, pitchDeg, rollDeg)

                framesFlow.emit(
                    OrientationFrame(
                        timestampMs = now,
                        azimuthDeg = azimuthDeg,
                        pitchDeg = pitchDeg,
                        rollDeg = rollDeg,
                        forward = forwardVector,
                        rotationMatrix = rotationMatrix,
                        accuracy = SensorAccuracy.HIGH
                    )
                )

                val delayMs = latestConfig.frameThrottleMs.coerceAtLeast(16L)
                delay(delayMs)
            }
        }
    }

    override suspend fun stop() {
        generatorJob?.cancelAndJoin()
        generatorJob = null
    }

    override fun setZeroAzimuthOffset(deg: Float) {
        zeroOffsetDeg = deg
    }

    override fun setRemap(rotation: ScreenRotation) {
        screenRotation.set(rotation)
    }

    private fun computeForwardVector(azimuthDeg: Float, pitchDeg: Float): FloatArray {
        val azimuthRad = Math.toRadians(azimuthDeg.toDouble())
        val pitchRad = Math.toRadians(pitchDeg.toDouble())

        val x = (cos(pitchRad) * sin(azimuthRad)).toFloat()
        val y = (cos(pitchRad) * cos(azimuthRad)).toFloat()
        val z = sin(pitchRad).toFloat()

        val magnitude = sqrt((x * x) + (y * y) + (z * z))
        val scale = if (magnitude == 0f) 1f else 1f / magnitude
        return floatArrayOf(x * scale, y * scale, z * scale)
    }

    private fun computeRotationMatrix(
        azimuthDeg: Float,
        pitchDeg: Float,
        rollDeg: Float,
    ): FloatArray {
        val azimuthRad = Math.toRadians(azimuthDeg.toDouble())
        val pitchRad = Math.toRadians(pitchDeg.toDouble())
        val rollRad = Math.toRadians(rollDeg.toDouble())

        val sinZ = sin(azimuthRad)
        val cosZ = cos(azimuthRad)
        val sinY = sin(pitchRad)
        val cosY = cos(pitchRad)
        val sinX = sin(rollRad)
        val cosX = cos(rollRad)

        val r00 = (cosZ * cosY).toFloat()
        val r01 = (cosZ * sinY * sinX - sinZ * cosX).toFloat()
        val r02 = (cosZ * sinY * cosX + sinZ * sinX).toFloat()
        val r10 = (sinZ * cosY).toFloat()
        val r11 = (sinZ * sinY * sinX + cosZ * cosX).toFloat()
        val r12 = (sinZ * sinY * cosX - cosZ * sinX).toFloat()
        val r20 = (-sinY).toFloat()
        val r21 = (cosY * sinX).toFloat()
        val r22 = (cosY * cosX).toFloat()

        return floatArrayOf(
            r00, r01, r02,
            r10, r11, r12,
            r20, r21, r22,
        )
    }

    private fun applyScreenRotation(
        azimuthDeg: Float,
        pitchDeg: Float,
        rollDeg: Float,
        rotation: ScreenRotation,
    ): Triple<Float, Float, Float> {
        return when (rotation) {
            ScreenRotation.ROT_0 -> Triple(azimuthDeg, pitchDeg, rollDeg)
            ScreenRotation.ROT_90 -> Triple(normalizeDegrees(azimuthDeg + 90f), pitchDeg, normalizeDegreesSigned(rollDeg + 90f))
            ScreenRotation.ROT_180 -> Triple(normalizeDegrees(azimuthDeg + 180f), -pitchDeg, normalizeDegreesSigned(rollDeg + 180f))
            ScreenRotation.ROT_270 -> Triple(normalizeDegrees(azimuthDeg + 270f), pitchDeg, normalizeDegreesSigned(rollDeg - 90f))
        }
    }

    private fun normalizeDegrees(value: Float): Float {
        var deg = value % 360f
        if (deg < 0f) deg += 360f
        return deg
    }

    private fun normalizeDegreesSigned(value: Float): Float {
        var deg = value % 360f
        if (deg > 180f) deg -= 360f
        if (deg <= -180f) deg += 360f
        return deg
    }
}
