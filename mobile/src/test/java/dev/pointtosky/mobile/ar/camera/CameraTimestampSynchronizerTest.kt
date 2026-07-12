package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.FrameRotationPairingResult
import dev.pointtosky.core.astro.projection.camera.TimedRotationSample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * JVM tests for [CameraTimestampSynchronizer] (CAM-1d) — the mobile-side integration seam wiring
 * the pure pairing/history/diagnostics classes to production camera ([CameraPreview]'s
 * `onFrameMetadata` callback) and rotation (`rememberRotationFrame`'s `onRotationSample` callback)
 * callbacks. Uses fakes only, no CameraX/SensorManager/Compose.
 */
class CameraTimestampSynchronizerTest {
    private fun frame(timestampNanos: Long) =
        CameraFrameMetadata(
            timestampNanos = timestampNanos,
            bufferWidthPx = 1920,
            bufferHeightPx = 1080,
            rotationDegrees = 0,
        )

    private fun rotationSample(timestampNanos: Long) =
        TimedRotationSample(timestampNanos = timestampNanos, rotationMatrix = FloatArray(9))

    @Test
    fun `a rotation sample arriving before a frame produces a successful pair`() {
        val synchronizer = CameraTimestampSynchronizer()

        synchronizer.onRotationSample(rotationSample(1_000L))
        synchronizer.onCameraFrame(frame(1_000L))

        val result = assertIs<FrameRotationPairingResult.Paired>(synchronizer.latestResult.value)
        assertEquals(1_000L, result.pair.rotation.timestampNanos)
    }

    @Test
    fun `a frame arriving before any rotation sample yields NoSamples`() {
        val synchronizer = CameraTimestampSynchronizer()

        synchronizer.onCameraFrame(frame(1_000L))

        assertIs<FrameRotationPairingResult.NoSamples>(synchronizer.latestResult.value)
    }

    @Test
    fun `reset clears stale history so a previous session's rotation sample cannot pair with a new session's first frame`() {
        val synchronizer = CameraTimestampSynchronizer()
        synchronizer.onRotationSample(rotationSample(1_000L))
        synchronizer.onCameraFrame(frame(1_000L))
        assertIs<FrameRotationPairingResult.Paired>(synchronizer.latestResult.value)

        synchronizer.reset()
        assertNull(synchronizer.latestResult.value)

        synchronizer.onCameraFrame(frame(1_000L))

        assertIs<FrameRotationPairingResult.NoSamples>(synchronizer.latestResult.value)
    }

    @Test
    fun `reset also clears accumulated diagnostics`() {
        val synchronizer = CameraTimestampSynchronizer()
        synchronizer.onRotationSample(rotationSample(1_000L))
        synchronizer.onCameraFrame(frame(1_000L))

        synchronizer.reset()
        val state = synchronizer.debugState()

        assertEquals(0L, state.observedFrameCount)
        assertEquals(0L, state.pairedFrameCount)
    }

    @Test
    fun `mutating the caller's rotation matrix after onRotationSample does not affect a later pair`() {
        val synchronizer = CameraTimestampSynchronizer()
        val matrix = FloatArray(9) { 1f }
        synchronizer.onRotationSample(TimedRotationSample(timestampNanos = 10L, rotationMatrix = matrix))
        matrix[0] = 999f

        synchronizer.onCameraFrame(frame(10L))

        val result = assertIs<FrameRotationPairingResult.Paired>(synchronizer.latestResult.value)
        assertEquals(1f, result.pair.rotation.rotationMatrix[0])
    }

    @Test
    fun `only the latest pairing result is retained, not a queue of every pair`() {
        val synchronizer = CameraTimestampSynchronizer()
        synchronizer.onRotationSample(rotationSample(0L))

        repeat(50) { i -> synchronizer.onCameraFrame(frame(i.toLong())) }

        val result = assertIs<FrameRotationPairingResult.Paired>(synchronizer.latestResult.value)
        assertEquals(49L, result.pair.frame.timestampNanos)
        assertEquals(50L, synchronizer.debugState().observedFrameCount)
    }

    @Test
    fun `onCameraFrame does not catch or swallow exceptions - CancellationException propagates unimpeded`() {
        // No try/catch wraps pairing/diagnostics inside onCameraFrame, so any exception - including
        // a CancellationException from a coroutine this is eventually called from - propagates to
        // the caller instead of being swallowed. An invalid threshold configuration is a convenient,
        // deterministic way to force pairFrameToNearestRotation to throw without needing a real
        // coroutine.
        val synchronizer = CameraTimestampSynchronizer(maxAllowedDeltaNanos = -1L)

        assertFailsWith<IllegalArgumentException> { synchronizer.onCameraFrame(frame(1_000L)) }
    }
}
