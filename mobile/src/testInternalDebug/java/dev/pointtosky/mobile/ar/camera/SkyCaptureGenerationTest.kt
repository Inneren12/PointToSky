package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKY-1 (`internalDebug`-only): the bind-generation rule.
 *
 * The failure this prevents is quiet: a frame still draining out of the previous bind's analysis
 * queue reaches a resolver whose first answer is cached forever, and the new session's header then
 * pairs the new camera id with the old camera's calibration. Both halves look plausible on their own,
 * so nothing downstream can catch it — the rule has to be right here.
 */
class SkyCaptureGenerationTest {
    private val exposure =
        SkyResolvedExposure(exposureTimeNanos = 500_000_000L, sensitivityIso = 1600, frameDurationNanos = 500_000_000L)

    private fun configuration(
        cameraId: String = "3",
        widthPx: Int = 1280,
        heightPx: Int = 720,
        exposure: SkyResolvedExposure? = this.exposure,
    ) = SkyCaptureConfiguration(
        physicalCameraId = cameraId,
        resolution = AnalysisResolutionRequest(widthPx, heightPx, AnalysisResolutionFamily.NEAR_16_9),
        exposure = exposure,
    )

    @Test
    fun `the first bind starts a generation`() {
        val tracker = SkyCaptureGenerationTracker()

        assertEquals(SkyGenerationTransition.STARTED, tracker.observe(1L, configuration()))
        assertEquals(1L, tracker.currentEpoch)
        assertEquals(configuration(), tracker.currentConfiguration)
    }

    @Test
    fun `repeat contact from the live bind is current, not a restart`() {
        val tracker = SkyCaptureGenerationTracker()
        tracker.observe(1L, configuration())

        assertEquals(SkyGenerationTransition.CURRENT, tracker.observe(1L, configuration()))
        assertEquals(SkyGenerationTransition.CURRENT, tracker.observe(1L, configuration()))
    }

    @Test
    fun `a newer bind starts a new generation`() {
        val tracker = SkyCaptureGenerationTracker()
        tracker.observe(1L, configuration(cameraId = "3"))

        assertEquals(SkyGenerationTransition.STARTED, tracker.observe(2L, configuration(cameraId = "0")))
        assertEquals("0", tracker.currentConfiguration?.physicalCameraId)
    }

    @Test
    fun `a stale epoch is rejected and cannot reinstate itself`() {
        val tracker = SkyCaptureGenerationTracker()
        tracker.observe(1L, configuration(cameraId = "3"))
        tracker.observe(2L, configuration(cameraId = "0"))

        assertEquals(SkyGenerationTransition.STALE, tracker.observe(1L, configuration(cameraId = "3")))
        assertEquals(2L, tracker.currentEpoch, "a late callback must never roll the live generation back")
        assertEquals("0", tracker.currentConfiguration?.physicalCameraId)
    }

    @Test
    fun `staleness is decided by epoch, not by configuration equality`() {
        // Same configuration, two binds - a stop/start cycle on identical settings. The older epoch is
        // still stale: its resolver, geometry provider and pairing history all belong to a bind that
        // has been torn down.
        val tracker = SkyCaptureGenerationTracker()
        tracker.observe(1L, configuration())
        tracker.observe(2L, configuration())

        assertEquals(SkyGenerationTransition.STALE, tracker.observe(1L, configuration()))
    }

    @Test
    fun `isCurrent tracks the live epoch and rejects zero`() {
        val tracker = SkyCaptureGenerationTracker()

        assertFalse(tracker.isCurrent(0L))
        assertFalse(tracker.isCurrent(1L))

        tracker.observe(1L, configuration())
        assertTrue(tracker.isCurrent(1L))
        assertFalse(tracker.isCurrent(0L))
        assertFalse(tracker.isCurrent(2L))
    }

    @Test
    fun `clear forgets the live generation`() {
        val tracker = SkyCaptureGenerationTracker()
        tracker.observe(5L, configuration())

        tracker.clear()

        assertEquals(0L, tracker.currentEpoch)
        assertNull(tracker.currentConfiguration)
        assertFalse(tracker.isCurrent(5L))
    }

    @Test
    fun `a non-positive epoch is a programming error`() {
        val tracker = SkyCaptureGenerationTracker()

        assertFailsWith<IllegalArgumentException> { tracker.observe(0L, configuration()) }
        assertFailsWith<IllegalArgumentException> { tracker.observe(-1L, configuration()) }
    }

    // -----------------------------------------------------------------------------------------
    // What counts as a different configuration
    // -----------------------------------------------------------------------------------------

    @Test
    fun `each of the three axes makes a distinct configuration`() {
        val base = configuration()

        assertTrue(base != configuration(cameraId = "0"), "physical camera id")
        assertTrue(base != configuration(widthPx = 1920, heightPx = 1080), "analysis resolution")
        assertTrue(base != configuration(exposure = null), "manual exposure")
        assertTrue(
            base != configuration(exposure = exposure.copy(sensitivityIso = 800)),
            "a changed ISO is a changed capture request and therefore a rebind",
        )
        assertEquals(base, configuration(), "identical settings compare equal")
    }
}
