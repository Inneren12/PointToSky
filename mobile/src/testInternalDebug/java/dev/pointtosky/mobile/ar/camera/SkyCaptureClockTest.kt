package dev.pointtosky.mobile.ar.camera

import android.hardware.camera2.CameraMetadata
import dev.pointtosky.core.astro.projection.camera.skylog.SkyClock
import dev.pointtosky.core.astro.projection.camera.skylog.SkyClockRelationship
import dev.pointtosky.core.astro.projection.camera.skylog.alignPoseTimestampToFrameClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * SKY-1 (`internalDebug`-only): the camera-timestamp provenance mapping.
 *
 * These run on a plain JVM with no device, which is possible because [skyCameraTimestampSourceOf]
 * takes the raw `SENSOR_INFO_TIMESTAMP_SOURCE` value rather than a `CameraCharacteristics` — the
 * `CameraMetadata` constants below are `static final int`s, so they inline at compile time and need no
 * Android runtime. Only the characteristics *read* itself ([probeSkyCameraTimestampSource]) needs a
 * bound camera, and that is a one-line delegation to this mapping.
 *
 * What is being pinned is a claim, not a lookup table: the capture path may say "these clocks are
 * comparable" only when the platform says so, and must say nothing at all otherwise.
 */
class SkyCaptureClockTest {
    @Test
    fun `a REALTIME timestamp source is recognised`() {
        assertEquals(
            SkyCameraTimestampSource.REALTIME,
            skyCameraTimestampSourceOf(CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME),
        )
    }

    @Test
    fun `an UNKNOWN timestamp source is recognised as the platform value it is`() {
        assertEquals(
            SkyCameraTimestampSource.UNKNOWN,
            skyCameraTimestampSourceOf(CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_UNKNOWN),
        )
    }

    @Test
    fun `an unreadable characteristic is UNAVAILABLE, never optimistically REALTIME`() {
        assertEquals(SkyCameraTimestampSource.UNAVAILABLE, skyCameraTimestampSourceOf(null))
    }

    @Test
    fun `a value this build does not know is reported as unrecognised rather than folded into UNKNOWN`() {
        val futureConstant = 99

        assertEquals(SkyCameraTimestampSource.UNRECOGNIZED, skyCameraTimestampSourceOf(futureConstant))
        assertNotEquals(
            SkyCameraTimestampSource.UNKNOWN,
            skyCameraTimestampSourceOf(futureConstant),
            "a future Android constant is not the same thing as the platform's own UNKNOWN",
        )
    }

    // -----------------------------------------------------------------------------------------
    // From a timestamp source to a claim about the two clocks
    // -----------------------------------------------------------------------------------------

    @Test
    fun `REALTIME proves the frame and pose clocks are the same time base`() {
        val alignment = skyClockAlignmentFor(SkyCameraTimestampSource.REALTIME)

        assertEquals(SkyClockRelationship.SOURCE_PROVEN_COMPARABLE, alignment.relationship)
        assertEquals(SkyClock.CAMERA_SENSOR_NANOS, alignment.frameClock)
        assertEquals(SkyClock.SENSOR_EVENT_NANOS, alignment.poseClock)
        assertNull(alignment.poseToFrameOffsetNanos, "a proven zero must not masquerade as a measured one")
        assertEquals(1_234L, alignPoseTimestampToFrameClock(1_234L, alignment))
    }

    @Test
    fun `every other timestamp source yields an unalignable session`() {
        listOf(
            SkyCameraTimestampSource.UNKNOWN,
            SkyCameraTimestampSource.UNAVAILABLE,
            SkyCameraTimestampSource.UNRECOGNIZED,
        ).forEach { source ->
            val alignment = skyClockAlignmentFor(source)

            assertEquals(SkyClockRelationship.UNKNOWN, alignment.relationship, "source=$source")
            assertEquals(SkyClock.UNKNOWN, alignment.frameClock, "source=$source")
            assertNull(alignPoseTimestampToFrameClock(1_234L, alignment), "source=$source must not align")
        }
    }

    @Test
    fun `no timestamp source ever produces a measured offset`() {
        // The capture path performs no clock measurement at all. If one is ever added it must set
        // MEASURED_OFFSET deliberately, from an actual measurement - not arrive here by accident.
        SkyCameraTimestampSource.entries.forEach { source ->
            assertNotEquals(
                SkyClockRelationship.MEASURED_OFFSET,
                skyClockAlignmentFor(source).relationship,
                "source=$source must not claim a measurement this code does not perform",
            )
        }
    }

    @Test
    fun `the pose clock is named honestly even when the frame clock cannot be`() {
        // The pose side is read from SensorEvent.timestamp in RotationFrame.kt and is always known; it
        // is the camera that would not say. Blanking both would lose real information.
        assertEquals(
            SkyClock.SENSOR_EVENT_NANOS,
            skyClockAlignmentFor(SkyCameraTimestampSource.UNAVAILABLE).poseClock,
        )
    }
}
