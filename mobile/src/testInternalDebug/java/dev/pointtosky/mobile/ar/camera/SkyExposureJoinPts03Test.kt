package dev.pointtosky.mobile.ar.camera

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        assertEquals("4", matched.captureResult.logicalTruth.activePhysicalCameraId)
        assertEquals(Pts03EffectiveDistortionMode.Off, matched.captureResult.logicalTruth.effectiveDistortionMode())
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
                Pts03Fixtures.captureResult().logicalTruth.copy(sensorTimestampNanos = null),
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

    // -----------------------------------------------------------------------------------------
    // Finalize: the exported statistics include what was pending, exactly once
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a pending frame before finalize is counted as pending at stop in the final statistics`() {
        val join = SkyExposureJoin<Frame>()
        join.offerFrame(Frame(10L))
        val final = join.finalizeJoin()
        assertEquals(1L, final.framesPendingAtStopCount)
        assertEquals(0L, final.captureResultsPendingAtStopCount)
        assertEquals(0, join.pendingFrameCount)
    }

    @Test
    fun `a pending result before finalize is counted as pending at stop in the final statistics`() {
        val join = SkyExposureJoin<Frame>()
        join.offerExposure(Pts03Fixtures.captureResult(timestampNanos = 10L))
        assertEquals(1L, join.finalizeJoin().captureResultsPendingAtStopCount)
    }

    @Test
    fun `finalizing twice, or draining after finalize, never changes the counts`() {
        val join = SkyExposureJoin<Frame>()
        join.offerFrame(Frame(10L))
        join.offerExposure(Pts03Fixtures.captureResult(timestampNanos = 20L))
        val first = join.finalizeJoin()
        assertEquals(first, join.finalizeJoin())
        join.drain()
        assertEquals(first, join.statistics)
    }

    @Test
    fun `offers after finalize are ignored and counted separately, never as frames or results`() {
        val join = SkyExposureJoin<Frame>()
        val first = join.finalizeJoin()
        assertNull(join.offerFrame(Frame(10L)).matched)
        assertNull(join.offerExposure(Pts03Fixtures.captureResult(timestampNanos = 10L)).matched)
        val after = join.statistics
        assertEquals(first.analysisFrameCount, after.analysisFrameCount)
        assertEquals(first.captureResultCount, after.captureResultCount)
        assertEquals(2L, after.offersIgnoredAfterFinalizeCount)
        assertEquals(0, join.pendingFrameCount)
    }

    @Test
    fun `the finalized session state exports the pending-at-stop counts`() {
        val join = SkyExposureJoin<Frame>()
        join.offerFrame(Frame(10L))
        join.offerExposure(Pts03Fixtures.captureResult(timestampNanos = 20L))
        val state =
            initialFrameContentExperimentSessionState(attemptId = 1L, physicalCameraId = "3")
                .reducePts03Finalized(1L, join.finalizeJoin())
                .reducePts03Finalized(1L, join.finalizeJoin())
        val stats = state.pts03.joinStatistics
        assertEquals(1L, stats.framesPendingAtStopCount)
        assertEquals(1L, stats.captureResultsPendingAtStopCount)
        val json = buildPts03CameraTruthJson(state.pts03, Pts03CameraTruthExportTest.TEST_ENVIRONMENT, 0L, true)
        val root =
            kotlinx.serialization.json.Json
                .parseToJsonElement(json)
                .jsonObject
        val exported = root.getValue("joinStatistics").jsonObject
        assertEquals("1", exported.getValue("framesPendingAtStopCount").jsonPrimitive.content)
        assertEquals("1", exported.getValue("captureResultsPendingAtStopCount").jsonPrimitive.content)
        assertEquals(
            "true",
            root
                .getValue("session")
                .jsonObject
                .getValue("finalized")
                .jsonPrimitive.content,
        )
    }
}
