package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * PTS-03: the SKY-1 exact `SENSOR_TIMESTAMP` join now carries the full per-result snapshot and counts every
 * offer and release. The join rule itself is unchanged and is still pinned by [SkyExposureJoinTest].
 */
class SkyExposureJoinPts03Test {
    private data class Frame(
        override val sensorTimestampNanos: Long,
    ) : SkyJoinFrame

    @Test
    fun `the richer CaptureResult metadata survives the exact join unchanged`() {
        val join = SkyExposureJoin<Frame>()
        val result = Pts03Fixtures.captureResult(timestampNanos = 10L, activePhysicalId = "4", distortionMode = 0)

        join.offerFrame(Frame(10L))
        val matched = assertNotNull(join.offerExposure(result).matched)

        assertSame(result, matched.captureResult, "the very snapshot instance reaches the consumer")
        assertEquals("4", matched.captureResult.cameraTruth.activePhysicalCameraId)
        assertEquals(Pts03EffectiveDistortionMode.Off, matched.captureResult.cameraTruth.effectiveDistortionMode())
        assertSame(result.exposure, matched.exposure, "SKY-1's view is the same result's exposure half")
    }

    @Test
    fun `a near timestamp never joins, whichever side arrives first`() {
        val join = SkyExposureJoin<Frame>()
        join.offerFrame(Frame(1_000L))
        assertNull(join.offerExposure(Pts03Fixtures.captureResult(timestampNanos = 1_001L)).matched)
        assertNull(join.offerFrame(Frame(999L)).matched)
    }

    @Test
    fun `statistics count every offer and every release by cause`() {
        val join = SkyExposureJoin<Frame>(capacity = 2, maxWaitNanos = 1_000L)

        join.offerFrame(Frame(100L))
        join.offerExposure(Pts03Fixtures.captureResult(timestampNanos = 100L)) // matched
        join.offerFrame(Frame(200L))
        join.offerFrame(Frame(200L)) // duplicate frame
        join.offerExposure(Pts03Fixtures.captureResult(timestampNanos = 300L))
        join.offerExposure(Pts03Fixtures.captureResult(timestampNanos = 300L)) // duplicate result
        join.offerExposure(
            SkyCaptureResultSnapshot(
                Pts03Fixtures.exposure(null),
                Pts03Fixtures.captureResult().cameraTruth.copy(sensorTimestampNanos = null),
            ),
        ) // unkeyed
        join.offerExposure(Pts03Fixtures.captureResult(timestampNanos = 400L))
        join.offerExposure(Pts03Fixtures.captureResult(timestampNanos = 500L)) // evicts 300 (capacity 2)
        join.offerFrame(Frame(5_000L)) // now=5000: frame 200 and results 400, 500 time out
        join.drain() // frame 5000 pending at stop

        val s = join.statistics
        assertEquals(4L, s.analysisFrameCount)
        assertEquals(6L, s.captureResultCount)
        assertEquals(1L, s.matchedCount)
        assertEquals(1L, s.duplicateFrameTimestampCount)
        assertEquals(1L, s.duplicateCaptureTimestampCount)
        assertEquals(1L, s.unkeyedCaptureResultCount)
        assertEquals(1L, s.captureResultEvictedCount)
        assertEquals(1L, s.frameTimedOutCount)
        assertEquals(2L, s.captureResultTimedOutCount)
        assertEquals(0L, s.frameEvictedCount)
        assertEquals(1L, s.framesPendingAtStopCount)
        assertEquals(0L, s.captureResultsPendingAtStopCount)
        assertEquals(0.25, s.matchedFraction)
        // Every frame offer is accounted for exactly once.
        assertEquals(
            s.analysisFrameCount,
            s.matchedCount + s.frameTimedOutCount + s.frameEvictedCount + s.duplicateFrameTimestampCount +
                s.framesPendingAtStopCount,
        )
    }

    @Test
    fun `matchedFraction is null before any frame, never zero`() {
        assertNull(SkyExposureJoin<Frame>().statistics.matchedFraction)
        assertEquals(SkyJoinStatistics.EMPTY, SkyExposureJoin<Frame>().statistics)
    }
}
