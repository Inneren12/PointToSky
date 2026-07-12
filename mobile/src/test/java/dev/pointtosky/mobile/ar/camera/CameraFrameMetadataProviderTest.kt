package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure JVM tests for [CameraFrameMetadataProvider] (CAM-1c) — verifies latest-value-only
 * publication (no unbounded queue: pushing N frames leaves exactly one, the most recent, behind)
 * and the debug counters.
 */
class CameraFrameMetadataProviderTest {
    private fun metadata(timestampNanos: Long) =
        CameraFrameMetadata(
            timestampNanos = timestampNanos,
            bufferWidthPx = 1920,
            bufferHeightPx = 1080,
            rotationDegrees = 0,
        )

    @Test
    fun `latest starts null before any frame`() {
        val provider = CameraFrameMetadataProvider()
        assertNull(provider.latest.value)
    }

    @Test
    fun `latest reflects only the most recently published frame`() {
        val provider = CameraFrameMetadataProvider()

        repeat(50) { i -> provider.onFrame(metadata(timestampNanos = i.toLong())) }

        // Latest-value semantics: no queue to drain, exactly the last pushed value survives.
        assertEquals(49L, provider.latest.value?.timestampNanos)
    }

    @Test
    fun `frame count increments once per onFrame call`() {
        val provider = CameraFrameMetadataProvider()

        repeat(7) { i -> provider.onFrame(metadata(timestampNanos = i.toLong())) }

        assertEquals(7L, provider.debugState().frameCount)
    }

    @Test
    fun `failed frame count increments only via recordFailedFrame`() {
        val provider = CameraFrameMetadataProvider()

        provider.onFrame(metadata(timestampNanos = 1L))
        provider.recordFailedFrame()
        provider.recordFailedFrame()

        val state = provider.debugState()
        assertEquals(1L, state.frameCount)
        assertEquals(2L, state.failedFrameCount)
    }

    @Test
    fun `debug state exposes the latest metadata alongside counters`() {
        val provider = CameraFrameMetadataProvider()

        provider.onFrame(metadata(timestampNanos = 5L))
        val state = provider.debugState()

        assertEquals(5L, state.latest?.timestampNanos)
        assertEquals(1L, state.frameCount)
        assertEquals(0L, state.failedFrameCount)
    }

    @Test
    fun `concurrent publication never loses thread safety - final state matches call count`() {
        val provider = CameraFrameMetadataProvider()
        val threads =
            (0 until 8).map { t ->
                Thread {
                    repeat(100) { i -> provider.onFrame(metadata(timestampNanos = (t * 100 + i).toLong())) }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }

        assertEquals(800L, provider.debugState().frameCount)
        // Latest-value semantics under concurrency: exactly one value is retained, not a queue.
        assertEquals(provider.latest.value, provider.debugState().latest)
    }
}
