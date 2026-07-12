package dev.pointtosky.core.astro.projection.camera

/**
 * How confidently CAM-1d's pairing has inferred that camera-frame and rotation-sensor timestamps
 * share a usable time base. This is a diagnostic inference, never proof: a handful of small deltas
 * does not confirm the two clocks are identical on this device, let alone on all Android devices,
 * and a single bad sample does not prove they differ.
 */
enum class TimestampCompatibility {
    /** Not enough evidence yet — the default/starting state, and the state after a [TimestampSyncDiagnostics.reset]. */
    UNKNOWN,

    /** A run of consecutive frames paired within tolerance was observed; treat as a working hypothesis, not a guarantee. */
    COMPATIBLE_OBSERVED,

    /** A run of consecutive clock-mismatch-scale deltas was observed; the two streams likely do not share a usable time base. */
    MISMATCH_SUSPECTED,
}

/**
 * Bounded, cheap, session-scoped snapshot of CAM-1d's pairing activity (CAM-1d). Never grows with
 * session length — every field is either a running count, a running min/max/mean, or the single
 * latest value.
 */
data class TimestampSyncDebugState(
    val observedFrameCount: Long,
    val pairedFrameCount: Long,
    val rejectedFrameCount: Long,
    val clockMismatchCount: Long,
    val latestDeltaNanos: Long?,
    val minAbsDeltaNanos: Long?,
    val maxAbsDeltaNanos: Long?,
    val meanAbsDeltaNanos: Double?,
    val compatibility: TimestampCompatibility,
)

/**
 * Session-scoped, thread-safe accumulator of [FrameRotationPairingResult]s (CAM-1d). Tracks only
 * bounded running statistics — counts, running min/max, a running sum/count for the mean, and the
 * latest delta — never every observed pair.
 *
 * Transition policy (deliberately conservative — see [TimestampCompatibility]'s kdoc):
 *  - [FrameRotationPairingResult.Paired] extends a "consecutive compatible" streak; once that streak
 *    reaches [minObservationsForCompatible], compatibility becomes [TimestampCompatibility.COMPATIBLE_OBSERVED]
 *    (unless already [TimestampCompatibility.MISMATCH_SUSPECTED] — a suspected mismatch is a stronger
 *    claim than a handful of subsequent good pairs, so it does not get silently overwritten by them).
 *  - [FrameRotationPairingResult.ClockMismatchSuspected] extends a "consecutive mismatch" streak;
 *    once that streak reaches [minConsecutiveMismatchesForSuspected], compatibility becomes
 *    [TimestampCompatibility.MISMATCH_SUSPECTED]. A single isolated mismatch result does not flip
 *    the whole session's verdict — only a run of them does.
 *  - [FrameRotationPairingResult.OutsideTolerance] and [FrameRotationPairingResult.NoSamples] affect
 *    counts (and, for `OutsideTolerance`, the delta statistics) but are neutral for compatibility:
 *    neither streak is touched, since they carry no direct evidence either way about whether the two
 *    clocks share a base — merely that a sample was outside tolerance, or that none existed yet.
 *  - Either streak resets to zero whenever the *other* kind of result is recorded, so compatibility
 *    reflects the most recent run of evidence, not a lifetime total.
 *
 * Delta statistics ([latestDeltaNanos], [TimestampSyncDebugState.minAbsDeltaNanos],
 * [TimestampSyncDebugState.maxAbsDeltaNanos], [TimestampSyncDebugState.meanAbsDeltaNanos]) are
 * updated from every result that carries a delta — [FrameRotationPairingResult.Paired],
 * [FrameRotationPairingResult.OutsideTolerance], and [FrameRotationPairingResult.ClockMismatchSuspected]
 * — not only successful pairs, so they reflect the full observed delta distribution.
 * [FrameRotationPairingResult.NoSamples] carries no delta and leaves them unchanged.
 */
class TimestampSyncDiagnostics(
    private val minObservationsForCompatible: Int = TimestampSyncConfig.MIN_OBSERVATIONS_FOR_COMPATIBLE,
    private val minConsecutiveMismatchesForSuspected: Int = TimestampSyncConfig.MIN_CONSECUTIVE_MISMATCHES_FOR_SUSPECTED,
) {
    init {
        require(minObservationsForCompatible > 0) {
            "minObservationsForCompatible must be positive; was $minObservationsForCompatible"
        }
        require(minConsecutiveMismatchesForSuspected > 0) {
            "minConsecutiveMismatchesForSuspected must be positive; was $minConsecutiveMismatchesForSuspected"
        }
    }

    private val lock = Any()

    private var observedFrameCount = 0L
    private var pairedFrameCount = 0L
    private var rejectedFrameCount = 0L
    private var clockMismatchCount = 0L
    private var latestDeltaNanos: Long? = null
    private var minAbsDeltaNanos: Long? = null
    private var maxAbsDeltaNanos: Long? = null
    private var sumAbsDeltaNanos = 0.0
    private var deltaSampleCount = 0L
    private var compatibility = TimestampCompatibility.UNKNOWN
    private var consecutiveCompatibleStreak = 0
    private var consecutiveMismatchStreak = 0

    /** Records one pairing attempt's outcome and returns the updated snapshot. */
    fun record(result: FrameRotationPairingResult): TimestampSyncDebugState =
        synchronized(lock) {
            observedFrameCount++
            when (result) {
                is FrameRotationPairingResult.Paired -> {
                    pairedFrameCount++
                    recordDelta(result.pair.deltaNanos)
                    consecutiveMismatchStreak = 0
                    consecutiveCompatibleStreak++
                    if (compatibility != TimestampCompatibility.MISMATCH_SUSPECTED &&
                        consecutiveCompatibleStreak >= minObservationsForCompatible
                    ) {
                        compatibility = TimestampCompatibility.COMPATIBLE_OBSERVED
                    }
                }

                is FrameRotationPairingResult.OutsideTolerance -> {
                    rejectedFrameCount++
                    recordDelta(result.deltaNanos)
                }

                is FrameRotationPairingResult.ClockMismatchSuspected -> {
                    rejectedFrameCount++
                    clockMismatchCount++
                    recordDelta(result.deltaNanos)
                    consecutiveCompatibleStreak = 0
                    consecutiveMismatchStreak++
                    if (consecutiveMismatchStreak >= minConsecutiveMismatchesForSuspected) {
                        compatibility = TimestampCompatibility.MISMATCH_SUSPECTED
                    }
                }

                is FrameRotationPairingResult.NoSamples -> {
                    rejectedFrameCount++
                }
            }
            snapshotLocked()
        }

    /** Current state without recording a new observation. */
    fun snapshot(): TimestampSyncDebugState = synchronized(lock) { snapshotLocked() }

    /** Resets every counter/statistic and compatibility back to its initial state (CAM-1d §8: new session). */
    fun reset() {
        synchronized(lock) {
            observedFrameCount = 0L
            pairedFrameCount = 0L
            rejectedFrameCount = 0L
            clockMismatchCount = 0L
            latestDeltaNanos = null
            minAbsDeltaNanos = null
            maxAbsDeltaNanos = null
            sumAbsDeltaNanos = 0.0
            deltaSampleCount = 0L
            compatibility = TimestampCompatibility.UNKNOWN
            consecutiveCompatibleStreak = 0
            consecutiveMismatchStreak = 0
        }
    }

    private fun recordDelta(deltaNanos: Long) {
        latestDeltaNanos = deltaNanos
        val absDelta = overflowSafeAbsNanos(deltaNanos)
        minAbsDeltaNanos = minAbsDeltaNanos?.let { minOf(it, absDelta) } ?: absDelta
        maxAbsDeltaNanos = maxAbsDeltaNanos?.let { maxOf(it, absDelta) } ?: absDelta
        sumAbsDeltaNanos += absDelta.toDouble()
        deltaSampleCount++
    }

    private fun snapshotLocked(): TimestampSyncDebugState =
        TimestampSyncDebugState(
            observedFrameCount = observedFrameCount,
            pairedFrameCount = pairedFrameCount,
            rejectedFrameCount = rejectedFrameCount,
            clockMismatchCount = clockMismatchCount,
            latestDeltaNanos = latestDeltaNanos,
            minAbsDeltaNanos = minAbsDeltaNanos,
            maxAbsDeltaNanos = maxAbsDeltaNanos,
            meanAbsDeltaNanos = if (deltaSampleCount > 0) sumAbsDeltaNanos / deltaSampleCount else null,
            compatibility = compatibility,
        )
}
