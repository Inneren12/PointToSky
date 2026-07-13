package dev.pointtosky.core.astro.projection.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure JVM tests for [TimestampSyncDiagnostics] (CAM-1d): conservative compatibility-state
 * transitions and running delta statistics.
 */
class TimestampSyncDiagnosticsTest {
    private fun paired(deltaNanos: Long) =
        FrameRotationPairingResult.Paired(
            FrameRotationPair(
                frame =
                    CameraFrameMetadata(
                        timestampNanos = 1_000L,
                        bufferWidthPx = 1920,
                        bufferHeightPx = 1080,
                        rotationDegrees = 0,
                    ),
                rotation = TimedRotationSample(timestampNanos = 1_000L, rotationMatrix = FloatArray(9)),
                deltaNanos = deltaNanos,
            ),
        )

    private fun mismatch(deltaNanos: Long) =
        FrameRotationPairingResult.ClockMismatchSuspected(
            frameTimestampNanos = deltaNanos,
            nearestRotationTimestampNanos = 0L,
            deltaNanos = deltaNanos,
        )

    private fun outsideTolerance(deltaNanos: Long) =
        FrameRotationPairingResult.OutsideTolerance(
            frameTimestampNanos = deltaNanos,
            nearestRotationTimestampNanos = 0L,
            deltaNanos = deltaNanos,
            maxAllowedDeltaNanos = 50_000_000L,
        )

    private fun noSamples() = FrameRotationPairingResult.NoSamples(frameTimestampNanos = 1_000L)

    @Test
    fun `starts UNKNOWN before any observation`() {
        val diagnostics = TimestampSyncDiagnostics()

        assertEquals(TimestampCompatibility.UNKNOWN, diagnostics.snapshot().compatibility)
    }

    @Test
    fun `becomes COMPATIBLE_OBSERVED only after the configured minimum consecutive paired observations`() {
        val diagnostics =
            TimestampSyncDiagnostics(minObservationsForCompatible = 5, minConsecutiveMismatchesForSuspected = 3)

        repeat(4) {
            val state = diagnostics.record(paired(1_000L))
            assertEquals(TimestampCompatibility.UNKNOWN, state.compatibility)
        }

        val fifth = diagnostics.record(paired(1_000L))
        assertEquals(TimestampCompatibility.COMPATIBLE_OBSERVED, fifth.compatibility)
    }

    @Test
    fun `one isolated clock mismatch does not flip an already-compatible session`() {
        val diagnostics =
            TimestampSyncDiagnostics(minObservationsForCompatible = 3, minConsecutiveMismatchesForSuspected = 3)
        repeat(3) { diagnostics.record(paired(1_000L)) }
        assertEquals(TimestampCompatibility.COMPATIBLE_OBSERVED, diagnostics.snapshot().compatibility)

        val afterIsolatedMismatch = diagnostics.record(mismatch(6_000_000_000L))

        assertEquals(TimestampCompatibility.COMPATIBLE_OBSERVED, afterIsolatedMismatch.compatibility)
    }

    @Test
    fun `repeated consecutive huge deltas produce MISMATCH_SUSPECTED`() {
        val diagnostics =
            TimestampSyncDiagnostics(minObservationsForCompatible = 5, minConsecutiveMismatchesForSuspected = 3)

        repeat(2) {
            val state = diagnostics.record(mismatch(6_000_000_000L))
            assertEquals(TimestampCompatibility.UNKNOWN, state.compatibility)
        }
        val third = diagnostics.record(mismatch(6_000_000_000L))

        assertEquals(TimestampCompatibility.MISMATCH_SUSPECTED, third.compatibility)
    }

    @Test
    fun `mismatch streak resets on an intervening paired result`() {
        val diagnostics =
            TimestampSyncDiagnostics(minObservationsForCompatible = 5, minConsecutiveMismatchesForSuspected = 3)

        diagnostics.record(mismatch(6_000_000_000L))
        diagnostics.record(mismatch(6_000_000_000L))
        diagnostics.record(paired(1_000L))
        diagnostics.record(mismatch(6_000_000_000L))
        val state = diagnostics.record(mismatch(6_000_000_000L))

        assertEquals(TimestampCompatibility.UNKNOWN, state.compatibility)
    }

    @Test
    fun `min max and mean absolute delta are tracked across paired, outside-tolerance, and mismatch results`() {
        val diagnostics = TimestampSyncDiagnostics()

        diagnostics.record(paired(10L))
        diagnostics.record(outsideTolerance(100_000_000L))
        val state = diagnostics.record(mismatch(6_000_000_000L))

        assertEquals(10L, state.minAbsDeltaNanos)
        assertEquals(6_000_000_000L, state.maxAbsDeltaNanos)
        assertEquals((10L + 100_000_000L + 6_000_000_000L) / 3.0, state.meanAbsDeltaNanos)
        assertEquals(6_000_000_000L, state.latestDeltaNanos)
    }

    @Test
    fun `NoSamples increments rejected count but leaves delta statistics untouched`() {
        val diagnostics = TimestampSyncDiagnostics()
        diagnostics.record(paired(10L))

        val state = diagnostics.record(noSamples())

        assertEquals(2L, state.observedFrameCount)
        assertEquals(1L, state.pairedFrameCount)
        assertEquals(1L, state.rejectedFrameCount)
        assertEquals(10L, state.latestDeltaNanos)
        assertEquals(10L, state.minAbsDeltaNanos)
        assertEquals(10L, state.maxAbsDeltaNanos)
    }

    @Test
    fun `reset clears every counter and compatibility back to the initial state`() {
        val diagnostics =
            TimestampSyncDiagnostics(minObservationsForCompatible = 1, minConsecutiveMismatchesForSuspected = 1)
        diagnostics.record(paired(10L))
        diagnostics.record(mismatch(6_000_000_000L))

        diagnostics.reset()
        val state = diagnostics.snapshot()

        assertEquals(0L, state.observedFrameCount)
        assertEquals(0L, state.pairedFrameCount)
        assertEquals(0L, state.rejectedFrameCount)
        assertEquals(0L, state.clockMismatchCount)
        assertNull(state.latestDeltaNanos)
        assertNull(state.minAbsDeltaNanos)
        assertNull(state.maxAbsDeltaNanos)
        assertNull(state.meanAbsDeltaNanos)
        assertEquals(TimestampCompatibility.UNKNOWN, state.compatibility)
    }
}
