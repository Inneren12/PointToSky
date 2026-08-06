package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * SKY-1 (`internalDebug`-only): the frame/exposure join.
 *
 * `ImageProxy` and `CaptureResult` arrive on different threads in either order, so the join must
 * complete a pair whichever side is second — and must never complete one from a *near* match. These
 * tests pin both directions, the boundedness that keeps pixel buffers from accumulating, and every
 * typed outcome.
 */
class SkyExposureJoinTest {
    private val fixtures = SkySessionCaptureFixtures

    private fun frameAt(timestampNanos: Long) = fixtures.analyzedFrame(timestampNanos = timestampNanos)

    private fun exposureAt(timestampNanos: Long?) =
        fixtures.exposureSample(sensorTimestampNanos = timestampNanos ?: 0L).copy(
            sensorTimestampNanos = timestampNanos,
        )

    // -----------------------------------------------------------------------------------------
    // Both arrival orders
    // -----------------------------------------------------------------------------------------

    @Test
    fun `image then result completes the pair`() {
        val join = SkyExposureJoin()
        val frame = frameAt(1_000L)

        assertNull(join.offerFrame(frame).matched, "the frame alone cannot complete a pair")
        val matched = assertNotNull(join.offerExposure(exposureAt(1_000L)).matched)

        assertSame(frame, matched.frame)
        assertEquals(1_000L, matched.exposure.sensorTimestampNanos)
        assertEquals(0, join.pendingFrameCount)
        assertEquals(0, join.pendingExposureCount)
    }

    @Test
    fun `result then image completes the pair`() {
        val join = SkyExposureJoin()

        assertNull(join.offerExposure(exposureAt(2_000L)).matched, "the result alone cannot complete a pair")
        val frame = frameAt(2_000L)
        val matched = assertNotNull(join.offerFrame(frame).matched)

        assertSame(frame, matched.frame)
        assertEquals(0, join.pendingFrameCount)
        assertEquals(0, join.pendingExposureCount)
    }

    @Test
    fun `interleaved frames and results pair up by timestamp, not by arrival order`() {
        val join = SkyExposureJoin()

        join.offerFrame(frameAt(10L))
        join.offerFrame(frameAt(20L))
        val second = assertNotNull(join.offerExposure(exposureAt(20L)).matched)
        val first = assertNotNull(join.offerExposure(exposureAt(10L)).matched)

        assertEquals(20L, second.frame.metadata.timestampNanos)
        assertEquals(10L, first.frame.metadata.timestampNanos)
    }

    @Test
    fun `a near-miss timestamp never matches`() {
        val join = SkyExposureJoin()

        join.offerFrame(frameAt(1_000L))
        val result = join.offerExposure(exposureAt(1_001L))

        assertNull(result.matched, "a one-nanosecond difference is a different frame, not a close enough one")
        assertEquals(1, join.pendingFrameCount)
        assertEquals(1, join.pendingExposureCount)
    }

    // -----------------------------------------------------------------------------------------
    // Typed drops
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a result with no sensor timestamp is dropped as unkeyed`() {
        val join = SkyExposureJoin()

        val result = join.offerExposure(exposureAt(null))

        assertEquals(listOf(SkyJoinDropReason.EXPOSURE_UNKEYED), result.dropped.map { it.reason })
        assertNull(result.dropped.single().frameTimestampNanos)
        assertEquals(0, join.pendingExposureCount)
    }

    @Test
    fun `a frame aged past the wait window times out`() {
        val join = SkyExposureJoin(capacity = 8, maxWaitNanos = 1_000L)
        join.offerFrame(frameAt(100L))

        // A much newer frame advances the join's notion of "now" past the wait window.
        val result = join.offerFrame(frameAt(5_000L))

        assertEquals(listOf(SkyJoinDropReason.FRAME_TIMED_OUT), result.dropped.map { it.reason })
        assertEquals(100L, result.dropped.single().frameTimestampNanos)
        assertEquals(1, join.pendingFrameCount)
    }

    @Test
    fun `a result aged past the wait window times out`() {
        val join = SkyExposureJoin(capacity = 8, maxWaitNanos = 1_000L)
        join.offerExposure(exposureAt(100L))

        val result = join.offerExposure(exposureAt(5_000L))

        assertEquals(listOf(SkyJoinDropReason.EXPOSURE_TIMED_OUT), result.dropped.map { it.reason })
    }

    @Test
    fun `a timed-out frame no longer matches its late result`() {
        val join = SkyExposureJoin(capacity = 8, maxWaitNanos = 1_000L)
        join.offerFrame(frameAt(100L))
        join.offerFrame(frameAt(5_000L))

        assertNull(join.offerExposure(exposureAt(100L)).matched, "an expired frame must not be resurrected")
    }

    @Test
    fun `pending frames evict oldest-first at capacity`() {
        val join = SkyExposureJoin(capacity = 3, maxWaitNanos = Long.MAX_VALUE / 4)
        (1L..3L).forEach { join.offerFrame(frameAt(it)) }

        val result = join.offerFrame(frameAt(4L))

        assertEquals(listOf(SkyJoinDropReason.FRAME_EVICTED), result.dropped.map { it.reason })
        assertEquals(1L, result.dropped.single().frameTimestampNanos)
        assertEquals(3, join.pendingFrameCount)
        assertNull(join.offerExposure(exposureAt(1L)).matched, "the evicted frame is gone")
        assertNotNull(join.offerExposure(exposureAt(4L)).matched)
    }

    @Test
    fun `pending exposures evict oldest-first at capacity`() {
        val join = SkyExposureJoin(capacity = 2, maxWaitNanos = Long.MAX_VALUE / 4)
        (1L..2L).forEach { join.offerExposure(exposureAt(it)) }

        val result = join.offerExposure(exposureAt(3L))

        assertEquals(listOf(SkyJoinDropReason.EXPOSURE_EVICTED), result.dropped.map { it.reason })
        assertEquals(2, join.pendingExposureCount)
    }

    @Test
    fun `memory stays bounded across a long run`() {
        val join = SkyExposureJoin(capacity = 4, maxWaitNanos = Long.MAX_VALUE / 4)

        // A thousand frames whose results never arrive: the join must not accumulate a thousand luma
        // planes.
        (1L..1_000L).forEach { join.offerFrame(frameAt(it)) }

        assertEquals(4, join.pendingFrameCount)
        assertEquals(0, join.pendingExposureCount)
    }

    // -----------------------------------------------------------------------------------------
    // Duplicate policy: first wins, on both sides
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a duplicate frame timestamp is refused and the first frame is kept`() {
        val join = SkyExposureJoin()
        val first = frameAt(7L)
        join.offerFrame(first)

        val result = join.offerFrame(frameAt(7L))

        assertEquals(listOf(SkyJoinDropReason.FRAME_DUPLICATE_TIMESTAMP), result.dropped.map { it.reason })
        assertEquals(1, join.pendingFrameCount)
        assertSame(first, assertNotNull(join.offerExposure(exposureAt(7L)).matched).frame)
    }

    @Test
    fun `a duplicate exposure timestamp is refused and the first sample is kept`() {
        val join = SkyExposureJoin()
        join.offerExposure(exposureAt(9L).copy(sensitivityIso = 100))

        val result = join.offerExposure(exposureAt(9L).copy(sensitivityIso = 6400))

        assertEquals(listOf(SkyJoinDropReason.EXPOSURE_DUPLICATE_TIMESTAMP), result.dropped.map { it.reason })
        assertEquals(100, assertNotNull(join.offerFrame(frameAt(9L)).matched).exposure.sensitivityIso)
    }

    @Test
    fun `a timestamp reused after its pair completed is treated as a fresh pending entry`() {
        val join = SkyExposureJoin()
        join.offerFrame(frameAt(5L))
        assertNotNull(join.offerExposure(exposureAt(5L)).matched)

        val again = join.offerFrame(frameAt(5L))

        assertEquals(emptyList(), again.dropped, "the slot was freed by the completed match")
        assertEquals(1, join.pendingFrameCount)
    }

    // -----------------------------------------------------------------------------------------
    // Teardown
    // -----------------------------------------------------------------------------------------

    @Test
    fun `drain reports everything still waiting and empties the join`() {
        val join = SkyExposureJoin()
        join.offerFrame(frameAt(1L))
        join.offerFrame(frameAt(2L))
        join.offerExposure(exposureAt(3L))

        val drops = join.drain()

        assertEquals(3, drops.size)
        assertTrue(drops.all { it.reason == SkyJoinDropReason.PENDING_AT_STOP })
        assertEquals(listOf(1L, 2L, 3L), drops.mapNotNull { it.frameTimestampNanos }.sorted())
        assertEquals(0, join.pendingFrameCount)
        assertEquals(0, join.pendingExposureCount)
    }

    @Test
    fun `drain on an empty join reports nothing`() {
        assertEquals(emptyList(), SkyExposureJoin().drain())
    }

    @Test
    fun `non-positive bounds are rejected`() {
        assertFailsWith<IllegalArgumentException> { SkyExposureJoin(capacity = 0) }
        assertFailsWith<IllegalArgumentException> { SkyExposureJoin(maxWaitNanos = 0L) }
    }
}
