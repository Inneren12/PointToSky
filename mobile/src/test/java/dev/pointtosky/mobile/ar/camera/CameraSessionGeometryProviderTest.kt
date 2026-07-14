package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.CameraGeometryQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryStatus
import dev.pointtosky.core.astro.projection.camera.FrameRotationPairingResult
import dev.pointtosky.core.astro.projection.camera.GeometryRejectionReason
import dev.pointtosky.core.astro.projection.camera.TimedRotationSample
import dev.pointtosky.core.astro.projection.camera.TimestampSyncConfig
import dev.pointtosky.core.astro.projection.camera.legacyFallbackCameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.pairFrameToNearestRotation
import dev.pointtosky.core.astro.projection.camera.status
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * JVM tests for [CameraSessionGeometryProvider] (CAM-1f): frame/pairing coherence, rebuild
 * triggers (viewport, intrinsics), latest-value-only publication, fallback-quality preservation,
 * and disposal races. Uses fakes/pure values only, no CameraX/Compose.
 */
class CameraSessionGeometryProviderTest {
    private fun frame(timestampNanos: Long) =
        CameraFrameMetadata(
            timestampNanos = timestampNanos,
            bufferWidthPx = 1920,
            bufferHeightPx = 1080,
            rotationDegrees = 0,
        )

    private fun pairing(
        frame: CameraFrameMetadata,
        rotationTimestampNanos: Long,
    ): FrameRotationPairingResult.Paired {
        val sample = TimedRotationSample(timestampNanos = rotationTimestampNanos, rotationMatrix = FloatArray(9))
        val result =
            pairFrameToNearestRotation(
                frame = frame,
                samples = listOf(sample),
                maxAllowedDeltaNanos = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
                clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
            )
        return assertIs<FrameRotationPairingResult.Paired>(result)
    }

    private val resolved =
        CameraIntrinsicsResolution.Resolved(
            CameraIntrinsics(
                horizontalFovDeg = 60.0,
                verticalFovDeg = 45.0,
                focalLengthMm = 4.25,
                sensorWidthMm = 5.76,
                sensorHeightMm = 4.29,
                principalPointXPx = null,
                principalPointYPx = null,
                source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
            ),
        )

    private val fallback =
        CameraIntrinsicsResolution.LegacyFallback(
            legacyFallbackCameraIntrinsics(imageWidthPx = 1920, imageHeightPx = 1080),
            reason = "no_valid_focal_length",
        )

    // --- Input ordering: unavailable-before-ready states -------------------------------------

    @Test
    fun `a frame before any viewport update reports InvalidViewport, not Ready`() {
        val provider = CameraSessionGeometryProvider()
        val f = frame(1_000L)

        provider.onPairedFrame(f, pairing(f, 1_000L))

        assertIs<CameraSessionGeometryResult.InvalidViewport>(provider.state.value)
    }

    @Test
    fun `a viewport update before any frame reports MissingFrame with the last known valid viewport`() {
        val provider = CameraSessionGeometryProvider()

        provider.onViewportChanged(1080, 1920)

        val missing = assertIs<CameraSessionGeometryResult.MissingFrame>(provider.state.value)
        assertEquals(1080.0, missing.viewportSize?.width)
        assertEquals(1920.0, missing.viewportSize?.height)
    }

    @Test
    fun `a paired frame after viewport and intrinsics are both known publishes Ready`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)
        val f = frame(1_000L)

        provider.onPairedFrame(f, pairing(f, 990L))

        val ready = assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)
        assertEquals(CameraGeometryQuality.CALIBRATED, ready.quality)
        assertEquals(f, ready.geometry.frame)
    }

    // --- Rebuild triggers ----------------------------------------------------------------------

    @Test
    fun `a viewport change rebuilds geometry against the same frame and pairing`() {
        val provider = CameraSessionGeometryProvider()
        provider.onIntrinsicsResolved(resolved)
        provider.onViewportChanged(1080, 1080)
        val f = frame(1_000L)
        provider.onPairedFrame(f, pairing(f, 1_000L))
        assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)

        provider.onViewportChanged(1920, 1080)

        val ready = assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)
        assertEquals(1920.0, ready.geometry.viewportSize.width)
        assertEquals(1080.0, ready.geometry.viewportSize.height)
        assertEquals(f, ready.geometry.frame)
    }

    @Test
    fun `intrinsics arriving after a frame rebuilds geometry from IntrinsicsUnavailable to Ready`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        val f = frame(1_000L)
        provider.onPairedFrame(f, pairing(f, 1_000L))
        assertIs<CameraSessionGeometryResult.IntrinsicsUnavailable>(provider.state.value)

        provider.onIntrinsicsResolved(resolved)

        assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)
    }

    @Test
    fun `a second onIntrinsicsResolved call is ignored - intrinsics resolve at most once per session`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)
        val f = frame(1_000L)
        provider.onPairedFrame(f, pairing(f, 1_000L))
        val firstReady = assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)
        assertEquals(CameraGeometryQuality.CALIBRATED, firstReady.quality)

        provider.onIntrinsicsResolved(fallback)

        val stillReady = assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)
        assertEquals(CameraGeometryQuality.CALIBRATED, stillReady.quality)
    }

    // --- Frame/pairing coherence ---------------------------------------------------------------

    @Test
    fun `the published bundle uses exactly the frame and pairing result passed together`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)
        val f = frame(5_000L)
        val pairingResult = pairing(f, rotationTimestampNanos = 4_970L)

        provider.onPairedFrame(f, pairingResult)

        val ready = assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)
        assertEquals(5_000L, ready.geometry.frame.timestampNanos)
        assertEquals(4_970L, ready.geometry.pairedRotation.timestampNanos)
        assertEquals(30L, ready.geometry.frameRotationDeltaNanos)
    }

    @Test
    fun `stale pairing computed for a different frame is rejected, never silently accepted`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)
        val f = frame(1_000L)
        val otherFrame = frame(2_000L)

        provider.onPairedFrame(f, pairing(otherFrame, 2_000L))

        val rejected = assertIs<CameraSessionGeometryResult.GeometryRejected>(provider.state.value)
        assertEquals(GeometryRejectionReason.PAIRING_FRAME_MISMATCH, rejected.reason)
    }

    // --- Latest-value-only, fallback preservation -----------------------------------------------

    @Test
    fun `only the latest bundle is retained, not a queue of every ready frame`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)

        repeat(20) { i ->
            val f = frame(1_000L + i)
            provider.onPairedFrame(f, pairing(f, 1_000L + i))
        }

        val ready = assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)
        assertEquals(1_019L, ready.geometry.frame.timestampNanos)
        assertEquals(20L, provider.debugState().observedFrameCount)
        assertEquals(20L, provider.debugState().readyBundleCount)
    }

    @Test
    fun `fallback intrinsics are preserved as a Ready bundle with fallback quality, never silently upgraded`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(fallback)
        val f = frame(1_000L)

        provider.onPairedFrame(f, pairing(f, 1_000L))

        val ready = assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)
        assertEquals(CameraGeometryQuality.LEGACY_INTRINSICS_FALLBACK, ready.quality)
        assertEquals(fallback, ready.geometry.intrinsics)
    }

    // --- Debug-state clearing on non-Ready results ----------------------------------------------

    @Test
    fun `Ready to InvalidViewport clears latestQuality and latestPairDeltaNanos`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)
        val f = frame(1_000L)
        provider.onPairedFrame(f, pairing(f, 1_000L))
        assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)
        val readyDebug = provider.debugState()
        assertEquals(CameraGeometryQuality.CALIBRATED, readyDebug.latestQuality)
        assertEquals(0L, readyDebug.latestPairDeltaNanos)

        provider.onViewportChanged(0, 0)

        assertIs<CameraSessionGeometryResult.InvalidViewport>(provider.state.value)
        val debug = provider.debugState()
        assertEquals(null, debug.latestQuality)
        assertEquals(null, debug.latestPairDeltaNanos)
        assertEquals(CameraSessionGeometryStatus.INVALID_VIEWPORT, debug.latestStatus)
    }

    @Test
    fun `Ready to RotationUnavailable clears latestQuality and latestPairDeltaNanos`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)
        val f = frame(1_000L)
        provider.onPairedFrame(f, pairing(f, 1_000L))
        assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)

        val staleFrame = frame(2_000L)
        val noSamples =
            pairFrameToNearestRotation(
                frame = staleFrame,
                samples = emptyList(),
                maxAllowedDeltaNanos = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
                clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
            )
        provider.onPairedFrame(staleFrame, noSamples)

        assertIs<CameraSessionGeometryResult.RotationUnavailable>(provider.state.value)
        val debug = provider.debugState()
        assertEquals(null, debug.latestQuality)
        assertEquals(null, debug.latestPairDeltaNanos)
        assertEquals(CameraSessionGeometryStatus.ROTATION_UNAVAILABLE, debug.latestStatus)
    }

    @Test
    fun `Ready to GeometryRejected clears latestQuality and latestPairDeltaNanos`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)
        val f = frame(1_000L)
        provider.onPairedFrame(f, pairing(f, 1_000L))
        assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)

        val staleFrame = frame(3_000L)
        val mismatchedPairing = pairing(frame(9_000L), 9_000L) // pairing computed for an unrelated frame
        provider.onPairedFrame(staleFrame, mismatchedPairing)

        val rejected = assertIs<CameraSessionGeometryResult.GeometryRejected>(provider.state.value)
        assertEquals(GeometryRejectionReason.PAIRING_FRAME_MISMATCH, rejected.reason)
        val debug = provider.debugState()
        assertEquals(null, debug.latestQuality)
        assertEquals(null, debug.latestPairDeltaNanos)
        assertEquals(CameraSessionGeometryStatus.GEOMETRY_REJECTED, debug.latestStatus)
    }

    @Test
    fun `Ready to Disposed clears latestQuality and latestPairDeltaNanos`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)
        val f = frame(1_000L)
        provider.onPairedFrame(f, pairing(f, 1_000L))
        assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)

        provider.dispose()

        assertEquals(CameraSessionGeometryResult.Disposed, provider.state.value)
        val debug = provider.debugState()
        assertEquals(null, debug.latestQuality)
        assertEquals(null, debug.latestPairDeltaNanos)
        assertEquals(CameraSessionGeometryStatus.DISPOSED, debug.latestStatus)
    }

    @Test
    fun `a subsequent Ready repopulates latestQuality and latestPairDeltaNanos while the provider is active`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)
        val f = frame(1_000L)
        provider.onPairedFrame(f, pairing(f, 1_000L))
        assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)

        // Drive the provider into a non-Ready state, clearing the debug fields.
        provider.onViewportChanged(0, 0)
        assertIs<CameraSessionGeometryResult.InvalidViewport>(provider.state.value)
        assertEquals(null, provider.debugState().latestQuality)

        // Restoring a valid viewport rebuilds against the still-latest frame/pairing pair.
        provider.onViewportChanged(1080, 1920)

        val ready = assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)
        val debug = provider.debugState()
        assertEquals(CameraGeometryQuality.CALIBRATED, debug.latestQuality)
        assertEquals(ready.geometry.frameRotationDeltaNanos, debug.latestPairDeltaNanos)
    }

    // --- Disposal --------------------------------------------------------------------------------

    @Test
    fun `dispose publishes Disposed and clears debug counters`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)
        val f = frame(1_000L)
        provider.onPairedFrame(f, pairing(f, 1_000L))
        assertIs<CameraSessionGeometryResult.Ready>(provider.state.value)

        provider.dispose()

        assertEquals(CameraSessionGeometryResult.Disposed, provider.state.value)
        val debug = provider.debugState()
        assertEquals(0L, debug.observedFrameCount)
        assertEquals(0L, debug.readyBundleCount)
        assertEquals(CameraSessionGeometryStatus.DISPOSED, debug.latestStatus)
    }

    @Test
    fun `callbacks after dispose are no-ops`() {
        val provider = CameraSessionGeometryProvider()
        provider.dispose()

        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)
        val f = frame(1_000L)
        provider.onPairedFrame(f, pairing(f, 1_000L))

        assertEquals(CameraSessionGeometryResult.Disposed, provider.state.value)
        assertEquals(0L, provider.debugState().observedFrameCount)
    }

    @Test
    fun `dispose is idempotent`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)

        provider.dispose()
        provider.dispose()
        provider.dispose()

        assertEquals(CameraSessionGeometryResult.Disposed, provider.state.value)
    }

    @Test
    fun `racing onPairedFrame against dispose never leaves a published bundle after disposal completes`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(RACE_ITERATIONS) {
                val provider = CameraSessionGeometryProvider()
                provider.onViewportChanged(1080, 1920)
                provider.onIntrinsicsResolved(resolved)
                val f = frame(1_000L)
                val pairingResult = pairing(f, 1_000L)
                val barrier = CyclicBarrier(2)

                val frameFuture =
                    executor.submit {
                        barrier.await()
                        provider.onPairedFrame(f, pairingResult)
                    }
                val disposeFuture =
                    executor.submit {
                        barrier.await()
                        provider.dispose()
                    }

                frameFuture.get(5, TimeUnit.SECONDS)
                disposeFuture.get(5, TimeUnit.SECONDS)

                // dispose() has definitely completed by now, regardless of which side won the race:
                // either onPairedFrame published Ready before dispose() overwrote it, or dispose()
                // completed first and onPairedFrame's later publication observed disposed == true and
                // no-opped. Either way, the end state must be the fully-disposed one.
                assertEquals(CameraSessionGeometryResult.Disposed, provider.state.value)
                val debug = provider.debugState()
                assertEquals(0L, debug.observedFrameCount)
                assertEquals(0L, debug.readyBundleCount)
            }
        } finally {
            executor.shutdown()
        }
    }

    @Test
    fun `racing onViewportChanged against dispose never leaves a published bundle after disposal completes`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(RACE_ITERATIONS) {
                val provider = CameraSessionGeometryProvider()
                provider.onIntrinsicsResolved(resolved)
                val f = frame(1_000L)
                provider.onPairedFrame(f, pairing(f, 1_000L))
                val barrier = CyclicBarrier(2)

                val viewportFuture =
                    executor.submit {
                        barrier.await()
                        provider.onViewportChanged(1080, 1920)
                    }
                val disposeFuture =
                    executor.submit {
                        barrier.await()
                        provider.dispose()
                    }

                viewportFuture.get(5, TimeUnit.SECONDS)
                disposeFuture.get(5, TimeUnit.SECONDS)

                assertEquals(CameraSessionGeometryResult.Disposed, provider.state.value)
            }
        } finally {
            executor.shutdown()
        }
    }

    // --- Observation: equal-result recomputes still surface updated counters (CAM-1g) -----------

    @Test
    fun `observation emits updated counters even though the result stays IntrinsicsUnavailable PENDING`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        val frameA = frame(1_000L)

        provider.onPairedFrame(frameA, pairing(frameA, 1_000L))
        val first = provider.observation.value
        assertIs<CameraSessionGeometryResult.IntrinsicsUnavailable>(first.result)
        assertEquals(1L, first.debugState.observedFrameCount)

        val frameB = frame(2_000L)
        provider.onPairedFrame(frameB, pairing(frameB, 2_000L))
        val second = provider.observation.value

        assertIs<CameraSessionGeometryResult.IntrinsicsUnavailable>(second.result)
        assertEquals(2L, second.debugState.observedFrameCount)
        assertNotEquals(first, second)
    }

    @Test
    fun `observation emits updated counters even though the result stays RotationUnavailable`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)

        val frameA = frame(1_000L)
        provider.onPairedFrame(frameA, noSamplesPairing(frameA))
        val first = provider.observation.value
        assertIs<CameraSessionGeometryResult.RotationUnavailable>(first.result)

        val frameB = frame(2_000L)
        provider.onPairedFrame(frameB, noSamplesPairing(frameB))
        val second = provider.observation.value

        assertIs<CameraSessionGeometryResult.RotationUnavailable>(second.result)
        assertEquals(first.result.status, second.result.status)
        assertTrue(second.debugState.observedFrameCount > first.debugState.observedFrameCount)
        assertNotEquals(first, second)
        // Not exercised by calling debugState() directly - the updated counter must already be
        // visible on the observation value itself.
        assertEquals(second.debugState, provider.debugState())
    }

    @Test
    fun `repeated Ready bundles update observation counts, retaining only the latest bundle`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)

        repeat(5) { i ->
            val f = frame(1_000L + i)
            provider.onPairedFrame(f, pairing(f, 1_000L + i))
        }

        val observation = provider.observation.value
        val ready = assertIs<CameraSessionGeometryResult.Ready>(observation.result)
        assertEquals(1_004L, ready.geometry.frame.timestampNanos)
        assertEquals(5L, observation.debugState.observedFrameCount)
        assertEquals(5L, observation.debugState.readyBundleCount)
    }

    @Test
    fun `disposal publishes a Disposed observation with cleared counters, is idempotent, and ignores post-disposal callbacks`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)
        val f = frame(1_000L)
        provider.onPairedFrame(f, pairing(f, 1_000L))
        assertIs<CameraSessionGeometryResult.Ready>(provider.observation.value.result)

        provider.dispose()

        val disposedObservation = provider.observation.value
        assertEquals(CameraSessionGeometryResult.Disposed, disposedObservation.result)
        assertEquals(0L, disposedObservation.debugState.observedFrameCount)
        assertEquals(0L, disposedObservation.debugState.readyBundleCount)
        assertEquals(CameraSessionGeometryStatus.DISPOSED, disposedObservation.debugState.latestStatus)

        provider.onPairedFrame(f, pairing(f, 1_000L))
        provider.onViewportChanged(1920, 1080)
        provider.onIntrinsicsResolved(fallback)
        assertEquals(disposedObservation, provider.observation.value)

        provider.dispose()
        assertEquals(disposedObservation, provider.observation.value)
    }

    // --- Observation/result/debugState coherence ---------------------------------------------------

    @Test
    fun `observation coherence - non-Ready result status matches debugState, quality and delta are null`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        val f = frame(1_000L)
        provider.onPairedFrame(f, pairing(f, 1_000L))

        val observation = provider.observation.value

        assertEquals(observation.result.status, observation.debugState.latestStatus)
        assertEquals(null, observation.debugState.latestQuality)
        assertEquals(null, observation.debugState.latestPairDeltaNanos)
    }

    @Test
    fun `observation coherence - Ready result status, quality, and pair delta match debugState`() {
        val provider = CameraSessionGeometryProvider()
        provider.onViewportChanged(1080, 1920)
        provider.onIntrinsicsResolved(resolved)
        val f = frame(5_000L)
        provider.onPairedFrame(f, pairing(f, rotationTimestampNanos = 4_970L))

        val observation = provider.observation.value
        val ready = assertIs<CameraSessionGeometryResult.Ready>(observation.result)

        assertEquals(ready.status, observation.debugState.latestStatus)
        assertEquals(ready.quality, observation.debugState.latestQuality)
        assertEquals(ready.geometry.frameRotationDeltaNanos, observation.debugState.latestPairDeltaNanos)
    }

    private fun noSamplesPairing(frame: CameraFrameMetadata): FrameRotationPairingResult =
        pairFrameToNearestRotation(
            frame = frame,
            samples = emptyList(),
            maxAllowedDeltaNanos = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
            clockMismatchThresholdNanos = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
        )

    private companion object {
        const val RACE_ITERATIONS = 500
    }
}
