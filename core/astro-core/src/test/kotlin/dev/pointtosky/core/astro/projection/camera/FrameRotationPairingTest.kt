package dev.pointtosky.core.astro.projection.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Pure JVM tests for [pairFrameToNearestRotation] (CAM-1d): selection, tie-break, tolerance/mismatch
 * classification, and overflow safety near [Long.MAX_VALUE].
 */
class FrameRotationPairingTest {
    private val defaultMaxAllowedDeltaNanos = 50_000_000L // 50 ms
    private val defaultClockMismatchThresholdNanos = 5_000_000_000L // 5 s

    private fun frame(timestampNanos: Long) =
        CameraFrameMetadata(
            timestampNanos = timestampNanos,
            bufferWidthPx = 1920,
            bufferHeightPx = 1080,
            rotationDegrees = 0,
        )

    private fun sample(timestampNanos: Long) = TimedRotationSample(timestampNanos = timestampNanos, rotationMatrix = FloatArray(9))

    private fun pair(
        frameTimestampNanos: Long,
        sampleTimestamps: List<Long>,
        maxAllowedDeltaNanos: Long = defaultMaxAllowedDeltaNanos,
        clockMismatchThresholdNanos: Long = defaultClockMismatchThresholdNanos,
    ) = pairFrameToNearestRotation(
        frame = frame(frameTimestampNanos),
        samples = sampleTimestamps.map { sample(it) },
        maxAllowedDeltaNanos = maxAllowedDeltaNanos,
        clockMismatchThresholdNanos = clockMismatchThresholdNanos,
    )

    @Test
    fun `exact timestamp match pairs with delta zero`() {
        val result = pair(frameTimestampNanos = 1_000L, sampleTimestamps = listOf(1_000L))

        val paired = assertIs<FrameRotationPairingResult.Paired>(result)
        assertEquals(1_000L, paired.pair.rotation.timestampNanos)
        assertEquals(0L, paired.pair.deltaNanos)
    }

    @Test
    fun `nearest earlier sample is selected over a farther later sample`() {
        val result = pair(frameTimestampNanos = 1_000L, sampleTimestamps = listOf(990L, 1_100L))

        val paired = assertIs<FrameRotationPairingResult.Paired>(result)
        assertEquals(990L, paired.pair.rotation.timestampNanos)
        assertEquals(10L, paired.pair.deltaNanos)
    }

    @Test
    fun `nearest later sample is selected over a farther earlier sample`() {
        val result = pair(frameTimestampNanos = 1_000L, sampleTimestamps = listOf(900L, 1_010L))

        val paired = assertIs<FrameRotationPairingResult.Paired>(result)
        assertEquals(1_010L, paired.pair.rotation.timestampNanos)
        assertEquals(-10L, paired.pair.deltaNanos)
    }

    @Test
    fun `equal-distance tie prefers the earlier sample`() {
        val result = pair(frameTimestampNanos = 1_000L, sampleTimestamps = listOf(950L, 1_050L))

        val paired = assertIs<FrameRotationPairingResult.Paired>(result)
        assertEquals(950L, paired.pair.rotation.timestampNanos)
    }

    @Test
    fun `delta exactly at the allowed threshold pairs`() {
        val result =
            pair(
                frameTimestampNanos = 1_000_000_000L,
                sampleTimestamps = listOf(1_000_000_000L - defaultMaxAllowedDeltaNanos),
            )

        val paired = assertIs<FrameRotationPairingResult.Paired>(result)
        assertEquals(defaultMaxAllowedDeltaNanos, paired.pair.deltaNanos)
    }

    @Test
    fun `delta one nanosecond over the threshold is outside tolerance`() {
        val result =
            pair(
                frameTimestampNanos = 1_000_000_000L,
                sampleTimestamps = listOf(1_000_000_000L - defaultMaxAllowedDeltaNanos - 1L),
            )

        val outside = assertIs<FrameRotationPairingResult.OutsideTolerance>(result)
        assertEquals(defaultMaxAllowedDeltaNanos + 1L, outside.deltaNanos)
        assertEquals(defaultMaxAllowedDeltaNanos, outside.maxAllowedDeltaNanos)
    }

    @Test
    fun `no samples returns NoSamples carrying the frame timestamp`() {
        val result = pair(frameTimestampNanos = 12_345L, sampleTimestamps = emptyList())

        val noSamples = assertIs<FrameRotationPairingResult.NoSamples>(result)
        assertEquals(12_345L, noSamples.frameTimestampNanos)
    }

    @Test
    fun `delta beyond the clock mismatch threshold is suspected mismatch`() {
        val result =
            pair(
                frameTimestampNanos = defaultClockMismatchThresholdNanos + 1_000L,
                sampleTimestamps = listOf(0L),
            )

        val mismatch = assertIs<FrameRotationPairingResult.ClockMismatchSuspected>(result)
        assertEquals(defaultClockMismatchThresholdNanos + 1_000L, mismatch.deltaNanos)
    }

    @Test
    fun `delta exactly at the clock mismatch threshold is outside tolerance, not suspected mismatch`() {
        val result =
            pair(
                frameTimestampNanos = defaultClockMismatchThresholdNanos,
                sampleTimestamps = listOf(0L),
            )

        assertIs<FrameRotationPairingResult.OutsideTolerance>(result)
    }

    @Test
    fun `timestamps near Long MAX_VALUE do not overflow and pair correctly`() {
        val result =
            pair(
                frameTimestampNanos = Long.MAX_VALUE,
                sampleTimestamps = listOf(Long.MAX_VALUE - 10L),
            )

        val paired = assertIs<FrameRotationPairingResult.Paired>(result)
        assertEquals(10L, paired.pair.deltaNanos)
    }

    @Test
    fun `a huge span up to Long MAX_VALUE does not overflow and is suspected mismatch`() {
        val result = pair(frameTimestampNanos = Long.MAX_VALUE, sampleTimestamps = listOf(0L))

        val mismatch = assertIs<FrameRotationPairingResult.ClockMismatchSuspected>(result)
        assertTrue(mismatch.deltaNanos > 0L)
        assertEquals(Long.MAX_VALUE, mismatch.deltaNanos)
    }

    @Test
    fun `negative frame timestamps are rejected by CameraFrameMetadata validation`() {
        assertFailsWith<IllegalArgumentException> {
            CameraFrameMetadata(
                timestampNanos = -1L,
                bufferWidthPx = 1920,
                bufferHeightPx = 1080,
                rotationDegrees = 0,
            )
        }
    }

    @Test
    fun `negative rotation sample timestamps are rejected by TimedRotationSample validation`() {
        assertFailsWith<IllegalArgumentException> {
            TimedRotationSample(timestampNanos = -1L, rotationMatrix = FloatArray(9))
        }
    }

    @Test
    fun `maxAllowedDeltaNanos must be non-negative`() {
        assertFailsWith<IllegalArgumentException> {
            pair(frameTimestampNanos = 0L, sampleTimestamps = listOf(0L), maxAllowedDeltaNanos = -1L)
        }
    }

    @Test
    fun `clockMismatchThresholdNanos must be at least maxAllowedDeltaNanos`() {
        assertFailsWith<IllegalArgumentException> {
            pair(
                frameTimestampNanos = 0L,
                sampleTimestamps = listOf(0L),
                maxAllowedDeltaNanos = 100L,
                clockMismatchThresholdNanos = 50L,
            )
        }
    }
}
