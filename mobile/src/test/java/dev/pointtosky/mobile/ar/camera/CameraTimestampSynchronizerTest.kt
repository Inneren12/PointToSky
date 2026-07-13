package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.FrameRotationPairingResult
import dev.pointtosky.core.astro.projection.camera.TimedRotationSample
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    // --- Normal active session (pre-dispose) ------------------------------------------------

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

    // --- Constructor validation (eager, not deferred to the first camera frame) -------------

    @Test
    fun `constructor rejects a negative maxAllowedDeltaNanos instead of deferring to the first camera frame`() {
        // Also replaces a previous, mis-named "cancellation propagates" test: this never exercised
        // CancellationException, only IllegalArgumentException from an invalid threshold. Actual
        // CancellationException propagation is covered at the CameraFrameAnalyzer boundary
        // (CameraFrameAnalyzerTest), where callbacks are invoked from a coroutine-cancellable context.
        assertFailsWith<IllegalArgumentException> { CameraTimestampSynchronizer(maxAllowedDeltaNanos = -1L) }
    }

    @Test
    fun `constructor rejects a clockMismatchThresholdNanos smaller than maxAllowedDeltaNanos`() {
        assertFailsWith<IllegalArgumentException> {
            CameraTimestampSynchronizer(maxAllowedDeltaNanos = 100L, clockMismatchThresholdNanos = 50L)
        }
    }

    @Test
    fun `constructor rejects a non-positive historyCapacity`() {
        assertFailsWith<IllegalArgumentException> { CameraTimestampSynchronizer(historyCapacity = 0) }
    }

    // --- Post-dispose callbacks (no races) ---------------------------------------------------

    @Test
    fun `dispose clears history, diagnostics, and the published result`() {
        val synchronizer = CameraTimestampSynchronizer()
        synchronizer.onRotationSample(rotationSample(1_000L))
        synchronizer.onCameraFrame(frame(1_000L))
        assertIs<FrameRotationPairingResult.Paired>(synchronizer.latestResult.value)

        synchronizer.dispose()

        assertNull(synchronizer.latestResult.value)
        val state = synchronizer.debugState()
        assertEquals(0L, state.observedFrameCount)
        assertEquals(0L, state.pairedFrameCount)
    }

    @Test
    fun `onCameraFrame is a no-op after dispose - it does not even publish NoSamples`() {
        val synchronizer = CameraTimestampSynchronizer()
        synchronizer.dispose()

        synchronizer.onCameraFrame(frame(1_000L))

        assertNull(synchronizer.latestResult.value)
        assertEquals(0L, synchronizer.debugState().observedFrameCount)
    }

    @Test
    fun `onRotationSample is a no-op after dispose`() {
        val synchronizer = CameraTimestampSynchronizer()
        synchronizer.dispose()

        // Would pair if this sample had actually been recorded.
        synchronizer.onRotationSample(rotationSample(1_000L))
        synchronizer.onCameraFrame(frame(1_000L))

        assertNull(synchronizer.latestResult.value)
    }

    @Test
    fun `dispose is idempotent`() {
        val synchronizer = CameraTimestampSynchronizer()
        synchronizer.onRotationSample(rotationSample(1_000L))
        synchronizer.onCameraFrame(frame(1_000L))

        synchronizer.dispose()
        synchronizer.dispose()
        synchronizer.dispose()

        assertNull(synchronizer.latestResult.value)
        assertEquals(0L, synchronizer.debugState().observedFrameCount)
    }

    // --- Disposal races: the invariant must hold for either interleaving --------------------

    @Test
    fun `racing onCameraFrame against dispose never leaves a published result after disposal completes`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(RACE_ITERATIONS) {
                val synchronizer = CameraTimestampSynchronizer()
                // Recorded synchronously, before the race, so a frame callback that wins the race
                // against dispose() has a real pre-dispose sample available to pair against.
                synchronizer.onRotationSample(rotationSample(1_000L))
                val barrier = CyclicBarrier(2)

                val frameFuture =
                    executor.submit {
                        barrier.await()
                        synchronizer.onCameraFrame(frame(1_000L))
                    }
                val disposeFuture =
                    executor.submit {
                        barrier.await()
                        synchronizer.dispose()
                    }

                frameFuture.get(5, TimeUnit.SECONDS)
                disposeFuture.get(5, TimeUnit.SECONDS)

                // Whichever side won the race - the frame call publishing Paired before dispose()
                // clears it, or dispose() completing before the frame call ever gets to publish -
                // dispose() has definitely run by now (its future was joined), so the end state must
                // always be the fully-disposed one. There is no interleaving that leaves a stale
                // published result or non-zero counters behind.
                assertNull(synchronizer.latestResult.value)
                val state = synchronizer.debugState()
                assertEquals(0L, state.observedFrameCount)
                assertEquals(0L, state.pairedFrameCount)
            }
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `racing onRotationSample against dispose never lets a late sample be used after disposal`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(RACE_ITERATIONS) {
                val synchronizer = CameraTimestampSynchronizer()
                val barrier = CyclicBarrier(2)

                val rotationFuture =
                    executor.submit {
                        barrier.await()
                        synchronizer.onRotationSample(rotationSample(1_000L))
                    }
                val disposeFuture =
                    executor.submit {
                        barrier.await()
                        synchronizer.dispose()
                    }

                rotationFuture.get(5, TimeUnit.SECONDS)
                disposeFuture.get(5, TimeUnit.SECONDS)

                // dispose() has definitely completed by now, regardless of which side won the race, so
                // a subsequent onCameraFrame call must be a full no-op - not even a published NoSamples
                // result - whether or not the raced rotation sample made it into history first.
                synchronizer.onCameraFrame(frame(1_000L))
                assertNull(synchronizer.latestResult.value)
            }
        } finally {
            executor.shutdown()
        }
    }

    private companion object {
        const val RACE_ITERATIONS = 1000
    }
}
