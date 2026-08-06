package dev.pointtosky.core.astro.projection.camera.skylog

import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pure JVM tests for the SKY-1 frame/pose clock contract (tests §2): a pose and a frame on the same
 * clock pair directly; two different clocks pair only through a recorded offset, and never through
 * a silently assumed zero.
 */
class SkySessionLogClockAlignmentTest {
    private val fixtures = SkySessionLogFixtures

    private val sameClock =
        SkyClockAlignment(frameClock = SkyClock.CAMERA_SENSOR_NANOS, poseClock = SkyClock.CAMERA_SENSOR_NANOS)

    @Test
    fun `a pose on the frame clock needs no offset`() {
        assertEquals(1_000L, alignPoseTimestampToFrameClock(1_000L, sameClock))
    }

    @Test
    fun `a recorded offset is applied even when both clocks carry the same name`() {
        val alignment = sameClock.copy(poseToFrameOffsetNanos = 7_000L)

        assertEquals(8_000L, alignPoseTimestampToFrameClock(1_000L, alignment))
    }

    @Test
    fun `differing clocks with no measured offset refuse to align rather than assuming zero`() {
        val alignment =
            SkyClockAlignment(frameClock = SkyClock.CAMERA_SENSOR_NANOS, poseClock = SkyClock.SENSOR_EVENT_NANOS)

        assertNull(alignPoseTimestampToFrameClock(1_000L, alignment))
    }

    @Test
    fun `differing clocks with a measured offset align by exactly that offset`() {
        val alignment =
            SkyClockAlignment(
                frameClock = SkyClock.CAMERA_SENSOR_NANOS,
                poseClock = SkyClock.SENSOR_EVENT_NANOS,
                poseToFrameOffsetNanos = -3_500_000L,
            )

        assertEquals(996_500_000L, alignPoseTimestampToFrameClock(1_000_000_000L, alignment))
    }

    @Test
    fun `an unknown clock refuses to align even when both sides say unknown`() {
        val alignment = SkyClockAlignment(frameClock = SkyClock.UNKNOWN, poseClock = SkyClock.UNKNOWN)

        assertNull(alignPoseTimestampToFrameClock(1_000L, alignment))
    }

    @Test
    fun `alignment saturates instead of wrapping on overflow`() {
        val alignment = sameClock.copy(poseToFrameOffsetNanos = Long.MAX_VALUE)

        assertEquals(Long.MAX_VALUE, alignPoseTimestampToFrameClock(Long.MAX_VALUE, alignment))
    }

    // -----------------------------------------------------------------------------------------
    // The alignment as the replay path actually consumes it.
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a frame and pose sharing a timestamp rebuild a ready geometry`() {
        val header = fixtures.header(clockAlignment = sameClock)
        val record =
            fixtures.frameRecord(
                frame = fixtures.frameMetadata(timestampNanos = SkySessionLogFixtures.FRAME_TIMESTAMP_NANOS),
                pose = fixtures.pose(timestampNanos = SkySessionLogFixtures.FRAME_TIMESTAMP_NANOS),
            )

        val result = assertIs<CameraSessionGeometryResult.Ready>(rebuildSkyFrameGeometry(header, record))

        assertEquals(0L, result.geometry.frameRotationDeltaNanos)
    }

    @Test
    fun `an offset between the two clocks is applied when rebuilding the geometry`() {
        // The pose is recorded 40 ms "earlier" on its own clock purely because that clock runs
        // 40 ms behind the camera clock - after the recorded offset the two samples are simultaneous.
        val offsetNanos = 40_000_000L
        val header =
            fixtures.header(
                clockAlignment =
                    SkyClockAlignment(
                        frameClock = SkyClock.CAMERA_SENSOR_NANOS,
                        poseClock = SkyClock.SENSOR_EVENT_NANOS,
                        poseToFrameOffsetNanos = offsetNanos,
                    ),
            )
        val record =
            fixtures.frameRecord(
                frame = fixtures.frameMetadata(timestampNanos = SkySessionLogFixtures.FRAME_TIMESTAMP_NANOS),
                pose = fixtures.pose(timestampNanos = SkySessionLogFixtures.FRAME_TIMESTAMP_NANOS - offsetNanos),
            )

        val result = assertIs<CameraSessionGeometryResult.Ready>(rebuildSkyFrameGeometry(header, record))

        assertEquals(0L, result.geometry.frameRotationDeltaNanos, "the recorded offset must cancel the clock skew")
    }

    @Test
    fun `without the offset the same pair falls outside the session tolerance`() {
        val offsetNanos = 40_000_000L
        val header =
            fixtures.header(
                clockAlignment =
                    SkyClockAlignment(
                        frameClock = SkyClock.CAMERA_SENSOR_NANOS,
                        poseClock = SkyClock.SENSOR_EVENT_NANOS,
                        poseToFrameOffsetNanos = 0L,
                    ),
                maxPairDeltaNanos = 25_000_000L,
            )
        val record =
            fixtures.frameRecord(
                frame = fixtures.frameMetadata(timestampNanos = SkySessionLogFixtures.FRAME_TIMESTAMP_NANOS),
                pose = fixtures.pose(timestampNanos = SkySessionLogFixtures.FRAME_TIMESTAMP_NANOS - offsetNanos),
            )

        val result = rebuildSkyFrameGeometry(header, record)

        assertIs<CameraSessionGeometryResult.RotationUnavailable>(result)
    }

    @Test
    fun `an unalignable pose skips the frame instead of comparing incomparable clocks`() {
        val header =
            fixtures.header(
                clockAlignment =
                    SkyClockAlignment(
                        frameClock = SkyClock.CAMERA_SENSOR_NANOS,
                        poseClock = SkyClock.SENSOR_EVENT_NANOS,
                    ),
            )

        assertNull(rebuildSkyFrameGeometry(header, fixtures.frameRecord()))

        val replayed = assertIs<SkyFrameReplayResult.Skipped>(replaySkySessionFrame(header, fixtures.frameRecord()))
        assertEquals(SkyFrameReplaySkipReason.POSE_CLOCK_UNALIGNED, replayed.reason)
    }

    @Test
    fun `the recorded frame-to-pose delta is preserved verbatim for auditing`() {
        val record =
            fixtures.frameRecord(
                pose = fixtures.pose(timestampNanos = SkySessionLogFixtures.FRAME_TIMESTAMP_NANOS - 12_345L),
            )

        val parsed = assertIs<SkySessionLogLine.Frame>(parseSkySessionLogLine(encodeSkyFrameLine(record), 1)).record

        assertEquals(12_345L, parsed.pose.frameToPoseDeltaNanos)
    }
}
