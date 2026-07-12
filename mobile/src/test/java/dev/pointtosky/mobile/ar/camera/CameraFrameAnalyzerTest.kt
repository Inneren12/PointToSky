package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException

/**
 * Pure JVM tests for [CameraFrameAnalyzer] (CAM-1c), driven entirely through [FakeFrameMetadataSource]
 * and [analyzeSource] — no real `ImageProxy`, no CameraX, no plane/pixel access anywhere in this
 * suite. Locks the close-in-`finally` guarantee: the source is closed exactly once whether
 * extraction succeeds, extraction throws, or the sink throws.
 */
class CameraFrameAnalyzerTest {
    private class RecordingSink : CameraFrameMetadataSink {
        val received = mutableListOf<CameraFrameMetadata>()

        override fun onFrame(metadata: CameraFrameMetadata) {
            received += metadata
        }
    }

    private class ThrowingSink(private val throwable: Throwable) : CameraFrameMetadataSink {
        override fun onFrame(metadata: CameraFrameMetadata) {
            throw throwable
        }
    }

    @Test
    fun `sink receives exactly one metadata object on success`() {
        val sink = RecordingSink()
        val analyzer = CameraFrameAnalyzer(metadataSink = sink)
        val source = FakeFrameMetadataSource(timestampNanos = 42L, widthPx = 1920, heightPx = 1080, rotationDegrees = 0)

        analyzer.analyzeSource(source)

        assertEquals(1, sink.received.size)
        assertEquals(42L, sink.received.single().timestampNanos)
        assertEquals(1920, sink.received.single().bufferWidthPx)
    }

    @Test
    fun `frame closes exactly once after successful extraction`() {
        val analyzer = CameraFrameAnalyzer(metadataSink = RecordingSink())
        val source = FakeFrameMetadataSource()

        analyzer.analyzeSource(source)

        assertEquals(1, source.closeCount)
    }

    @Test
    fun `frame closes exactly once when extraction throws`() {
        val sink = RecordingSink()
        val analyzer = CameraFrameAnalyzer(metadataSink = sink)
        // Invalid rotation triggers CameraFrameMetadata validation to throw inside toCameraFrameMetadata().
        val source = FakeFrameMetadataSource(rotationDegrees = 45)

        analyzer.analyzeSource(source)

        assertEquals(1, source.closeCount)
        assertTrue(sink.received.isEmpty())
    }

    @Test
    fun `onFrameFailure callback runs when extraction throws`() {
        var failures = 0
        val analyzer = CameraFrameAnalyzer(metadataSink = RecordingSink(), onFrameFailure = { failures++ })
        val source = FakeFrameMetadataSource(widthPx = 0)

        analyzer.analyzeSource(source)

        assertEquals(1, failures)
    }

    @Test
    fun `frame closes exactly once when the sink throws`() {
        val source = FakeFrameMetadataSource()
        val analyzer = CameraFrameAnalyzer(metadataSink = ThrowingSink(IllegalStateException("sink boom")))

        analyzer.analyzeSource(source)

        assertEquals(1, source.closeCount)
    }

    @Test
    fun `sink exception does not propagate out of analyzeSource`() {
        val source = FakeFrameMetadataSource()
        val analyzer = CameraFrameAnalyzer(metadataSink = ThrowingSink(RuntimeException("sink boom")))

        // Must not throw - a crashing sink must not crash the camera analysis pipeline.
        analyzer.analyzeSource(source)
    }

    @Test
    fun `cancellation from the sink propagates instead of being swallowed`() {
        val source = FakeFrameMetadataSource()
        val analyzer = CameraFrameAnalyzer(metadataSink = ThrowingSink(CancellationException("cancelled")))

        assertFailsWith<CancellationException> { analyzer.analyzeSource(source) }
        assertEquals(1, source.closeCount)
    }
}
