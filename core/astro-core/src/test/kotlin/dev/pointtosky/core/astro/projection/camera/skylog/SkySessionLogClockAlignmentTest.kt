package dev.pointtosky.core.astro.projection.camera.skylog

import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pure JVM tests for the SKY-1 frame/pose clock contract.
 *
 * The rule these pin: a log may only claim its pose and frame timestamps are comparable on one of two
 * grounds — a documented source that proves they share a time base, or an offset the capture actually
 * measured. Everything else is unalignable, and unalignable never quietly degrades into zero.
 */
class SkySessionLogClockAlignmentTest {
    private val fixtures = SkySessionLogFixtures

    private val proven =
        SkyClockAlignment.sourceProvenComparable(
            frameClock = SkyClock.CAMERA_SENSOR_NANOS,
            poseClock = SkyClock.SENSOR_EVENT_NANOS,
        )

    // -----------------------------------------------------------------------------------------
    // Which claims a SkyClockAlignment is allowed to make at all
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a proven-comparable alignment must not carry an explicit offset`() {
        // Reserved spelling: an explicit 0 means "measured 0", and the two must stay distinguishable.
        assertFailsWith<IllegalArgumentException> {
            SkyClockAlignment(
                frameClock = SkyClock.CAMERA_SENSOR_NANOS,
                poseClock = SkyClock.SENSOR_EVENT_NANOS,
                relationship = SkyClockRelationship.SOURCE_PROVEN_COMPARABLE,
                poseToFrameOffsetNanos = 0L,
            )
        }
    }

    @Test
    fun `a clock that cannot be named cannot be proven comparable`() {
        assertFailsWith<IllegalArgumentException> {
            SkyClockAlignment(
                frameClock = SkyClock.UNKNOWN,
                poseClock = SkyClock.SENSOR_EVENT_NANOS,
                relationship = SkyClockRelationship.SOURCE_PROVEN_COMPARABLE,
            )
        }
    }

    @Test
    fun `a measured offset with no value is not a measurement`() {
        assertFailsWith<IllegalArgumentException> {
            SkyClockAlignment(
                frameClock = SkyClock.CAMERA_SENSOR_NANOS,
                poseClock = SkyClock.SENSOR_EVENT_NANOS,
                relationship = SkyClockRelationship.MEASURED_OFFSET,
            )
        }
    }

    @Test
    fun `an unknown relationship must not smuggle in an offset`() {
        assertFailsWith<IllegalArgumentException> {
            SkyClockAlignment(
                frameClock = SkyClock.UNKNOWN,
                poseClock = SkyClock.SENSOR_EVENT_NANOS,
                relationship = SkyClockRelationship.UNKNOWN,
                poseToFrameOffsetNanos = 5L,
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Alignment
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a source-proven pair of clocks aligns with a zero offset`() {
        assertEquals(1_000L, alignPoseTimestampToFrameClock(1_000L, proven))
    }

    @Test
    fun `identically named clocks are not comparable on that basis alone`() {
        // The enum names what a value was read from, never that two values may be subtracted. Only the
        // relationship decides, and this one says nothing was established.
        val sameName =
            SkyClockAlignment.unknown(
                frameClock = SkyClock.CAMERA_SENSOR_NANOS,
                poseClock = SkyClock.CAMERA_SENSOR_NANOS,
            )

        assertNull(alignPoseTimestampToFrameClock(1_000L, sameName))
    }

    @Test
    fun `differing clocks with no established relationship refuse to align rather than assuming zero`() {
        val alignment =
            SkyClockAlignment.unknown(
                frameClock = SkyClock.UNKNOWN,
                poseClock = SkyClock.SENSOR_EVENT_NANOS,
            )

        assertNull(alignPoseTimestampToFrameClock(1_000L, alignment))
    }

    @Test
    fun `an explicitly measured offset is applied exactly`() {
        val alignment =
            SkyClockAlignment.measuredOffset(
                frameClock = SkyClock.CAMERA_SENSOR_NANOS,
                poseClock = SkyClock.SENSOR_EVENT_NANOS,
                poseToFrameOffsetNanos = -3_500_000L,
            )

        assertEquals(996_500_000L, alignPoseTimestampToFrameClock(1_000_000_000L, alignment))
    }

    @Test
    fun `a measured offset of zero is a real measurement and aligns`() {
        // Distinct from SOURCE_PROVEN_COMPARABLE only in provenance - both align, and both say why.
        val alignment =
            SkyClockAlignment.measuredOffset(
                frameClock = SkyClock.CAMERA_SENSOR_NANOS,
                poseClock = SkyClock.SENSOR_EVENT_NANOS,
                poseToFrameOffsetNanos = 0L,
            )

        assertEquals(1_000L, alignPoseTimestampToFrameClock(1_000L, alignment))
    }

    @Test
    fun `alignment saturates instead of wrapping on overflow`() {
        val alignment =
            SkyClockAlignment.measuredOffset(
                frameClock = SkyClock.CAMERA_SENSOR_NANOS,
                poseClock = SkyClock.SENSOR_EVENT_NANOS,
                poseToFrameOffsetNanos = Long.MAX_VALUE,
            )

        assertEquals(Long.MAX_VALUE, alignPoseTimestampToFrameClock(Long.MAX_VALUE, alignment))
    }

    // -----------------------------------------------------------------------------------------
    // The alignment as the codec and the replay path actually consume it
    // -----------------------------------------------------------------------------------------

    @Test
    fun `the relationship survives a header round trip`() {
        val header =
            fixtures.header(
                clockAlignment =
                    SkyClockAlignment.measuredOffset(
                        frameClock = SkyClock.CAMERA_SENSOR_NANOS,
                        poseClock = SkyClock.SENSOR_EVENT_NANOS,
                        poseToFrameOffsetNanos = 40_000_000L,
                    ),
            )

        val parsed =
            assertIs<SkySessionLogLine.Header>(
                parseSkySessionLogLine(encodeSkySessionHeaderLine(header), 1),
            ).header

        assertEquals(SkyClockRelationship.MEASURED_OFFSET, parsed.clockAlignment.relationship)
        assertEquals(40_000_000L, parsed.clockAlignment.poseToFrameOffsetNanos)
    }

    @Test
    fun `a proven-comparable header round trips without growing an offset field`() {
        val line = encodeSkySessionHeaderLine(fixtures.header(clockAlignment = proven))

        val parsed = assertIs<SkySessionLogLine.Header>(parseSkySessionLogLine(line, 1)).header

        assertEquals(SkyClockRelationship.SOURCE_PROVEN_COMPARABLE, parsed.clockAlignment.relationship)
        assertNull(
            parsed.clockAlignment.poseToFrameOffsetNanos,
            "an implied zero must never be written out as an explicit one",
        )
    }

    @Test
    fun `a header with no relationship is unreadable rather than assumed comparable`() {
        val line =
            encodeSkySessionHeaderLine(fixtures.header(clockAlignment = proven))
                .replace(""","relationship":"SOURCE_PROVEN_COMPARABLE"""", "")

        assertIs<SkySessionLogLine.Unreadable>(parseSkySessionLogLine(line, 1))
    }

    @Test
    fun `a header claiming a measured offset without one is unreadable`() {
        val line =
            encodeSkySessionHeaderLine(fixtures.header(clockAlignment = proven))
                .replace("SOURCE_PROVEN_COMPARABLE", "MEASURED_OFFSET")

        assertIs<SkySessionLogLine.Unreadable>(parseSkySessionLogLine(line, 1))
    }

    @Test
    fun `a frame and pose sharing a timestamp rebuild a ready geometry under a proven alignment`() {
        val header = fixtures.header(clockAlignment = proven)
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
                    SkyClockAlignment.measuredOffset(
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
        val header = fixtures.header(clockAlignment = proven, maxPairDeltaNanos = 25_000_000L)
        val record =
            fixtures.frameRecord(
                frame = fixtures.frameMetadata(timestampNanos = SkySessionLogFixtures.FRAME_TIMESTAMP_NANOS),
                pose = fixtures.pose(timestampNanos = SkySessionLogFixtures.FRAME_TIMESTAMP_NANOS - offsetNanos),
            )

        val result = rebuildSkyFrameGeometry(header, record)

        assertIs<CameraSessionGeometryResult.RotationUnavailable>(result)
    }

    @Test
    fun `an unproven alignment skips the frame instead of comparing incomparable clocks`() {
        val header =
            fixtures.header(
                clockAlignment =
                    SkyClockAlignment.unknown(
                        frameClock = SkyClock.UNKNOWN,
                        poseClock = SkyClock.SENSOR_EVENT_NANOS,
                    ),
            )

        assertNull(rebuildSkyFrameGeometry(header, fixtures.frameRecord()))

        val replayed = assertIs<SkyFrameReplayResult.Skipped>(replaySkySessionFrame(header, fixtures.frameRecord()))
        assertEquals(SkyFrameReplaySkipReason.POSE_CLOCK_UNALIGNED, replayed.reason)
    }

    @Test
    fun `every frame of an unproven session is skipped, none silently replayed at zero offset`() {
        val header =
            fixtures.header(
                clockAlignment =
                    SkyClockAlignment.unknown(
                        frameClock = SkyClock.UNKNOWN,
                        poseClock = SkyClock.SENSOR_EVENT_NANOS,
                    ),
            )
        val records = (0L until 3L).map { fixtures.frameRecord(sequence = it) }

        val report = replaySkySessionLog(header, records)

        assertEquals(emptyList(), report.readyFrames)
        assertEquals(
            List(3) { SkyFrameReplaySkipReason.POSE_CLOCK_UNALIGNED },
            report.skippedFrames.map { it.reason },
        )
    }

    @Test
    fun `the recorded raw frame-to-pose delta is preserved verbatim for auditing`() {
        val record =
            fixtures.frameRecord(
                pose = fixtures.pose(timestampNanos = SkySessionLogFixtures.FRAME_TIMESTAMP_NANOS - 12_345L),
            )

        val parsed = assertIs<SkySessionLogLine.Frame>(parseSkySessionLogLine(encodeSkyFrameLine(record), 1)).record

        assertEquals(12_345L, parsed.pose.frameToPoseRawDeltaNanos)
    }
}
