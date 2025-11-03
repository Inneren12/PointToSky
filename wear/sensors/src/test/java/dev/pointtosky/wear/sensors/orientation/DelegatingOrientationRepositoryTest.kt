package dev.pointtosky.wear.sensors.orientation

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DelegatingOrientationRepositoryTest {

    @Test
    fun `uses rotation vector when available`() = runBlocking {
        val primary = FakeOrientationRepository(OrientationSource.ROTATION_VECTOR)
        val fallback = FakeOrientationRepository(OrientationSource.ACCEL_MAG)
        val proxy = DelegatingOrientationRepository(primary, fallback, scope = this)

        proxy.start(OrientationRepositoryConfig())

        val frame = OrientationFrame(
            timestampNanos = 1L,
            azimuthDeg = 0f,
            pitchDeg = 0f,
            rollDeg = 0f,
            forward = floatArrayOf(0f, 0f, 1f),
            accuracy = OrientationAccuracy.HIGH,
            rotationMatrix = FloatArray(9),
        )
        primary.emitFrame(frame)

        val received = withTimeout(1_000) { proxy.frames.first() }
        assertEquals(frame, received)
        assertTrue(primary.started)
        assertFalse(fallback.started)
        assertEquals(OrientationSource.ROTATION_VECTOR, proxy.activeSource.value)
    }

    @Test
    fun `falls back to accel plus mag when rotation vector missing`() = runBlocking {
        val fallback = FakeOrientationRepository(OrientationSource.ACCEL_MAG)
        val proxy = DelegatingOrientationRepository(primary = null, fallback = fallback, scope = this)

        proxy.start(OrientationRepositoryConfig())

        val frame = OrientationFrame(
            timestampNanos = 2L,
            azimuthDeg = 10f,
            pitchDeg = 5f,
            rollDeg = -2f,
            forward = floatArrayOf(0f, 1f, 0f),
            accuracy = OrientationAccuracy.MEDIUM,
            rotationMatrix = FloatArray(9),
        )
        fallback.emitFrame(frame)

        val received = withTimeout(1_000) { proxy.frames.first() }
        assertEquals(frame, received)
        assertTrue(fallback.started)
        assertEquals(OrientationSource.ACCEL_MAG, proxy.activeSource.value)
    }
}

private class FakeOrientationRepository(
    override val source: OrientationSource,
) : OrientationRepository {
    private val framesFlow = MutableSharedFlow<OrientationFrame>(replay = 1, extraBufferCapacity = 1)
    private val zeroFlow = MutableStateFlow(OrientationZero())
    private val fpsFlow = MutableStateFlow<Float?>(null)
    private val activeSourceFlow = MutableStateFlow(source)
    private val runningFlow = MutableStateFlow(false)

    var started: Boolean = false

    override val frames: Flow<OrientationFrame> = framesFlow
    override val zero: StateFlow<OrientationZero> = zeroFlow.asStateFlow()
    override val fps: StateFlow<Float?> = fpsFlow.asStateFlow()
    override val activeSource: StateFlow<OrientationSource> = activeSourceFlow.asStateFlow()
    override val isRunning: StateFlow<Boolean> = runningFlow.asStateFlow()

    override fun start(config: OrientationRepositoryConfig) {
        started = true
        runningFlow.value = true
    }

    override fun stop() {
        started = false
        runningFlow.value = false
    }

    override fun updateZero(orientationZero: OrientationZero) {
        zeroFlow.value = orientationZero
    }

    override fun setZeroAzimuthOffset(offsetDeg: Float) {
        zeroFlow.value = OrientationZero(azimuthOffsetDeg = offsetDeg)
    }

    override fun setRemap(screenRotation: ScreenRotation) = Unit

    override fun resetZero() {
        zeroFlow.value = OrientationZero()
    }

    suspend fun emitFrame(frame: OrientationFrame) {
        framesFlow.emit(frame)
    }
}
