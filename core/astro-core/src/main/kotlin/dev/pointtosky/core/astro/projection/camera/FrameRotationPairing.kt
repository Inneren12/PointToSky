package dev.pointtosky.core.astro.projection.camera

/**
 * A camera frame successfully paired with the rotation sample nearest to it in time (CAM-1d).
 * [deltaNanos] is signed: `frame.timestampNanos - rotation.timestampNanos`.
 */
data class FrameRotationPair(
    val frame: CameraFrameMetadata,
    val rotation: TimedRotationSample,
    val deltaNanos: Long,
)

/**
 * Outcome of attempting to pair one camera frame with the nearest available rotation sample
 * (CAM-1d). Deliberately not just a nullable [FrameRotationPair] — callers (diagnostics, logging)
 * need to distinguish *why* a frame went unpaired: no rotation data at all, a plausible-but-too-far
 * sample, or a delta so large it suggests the two clocks do not share a usable time base.
 */
sealed interface FrameRotationPairingResult {
    /** The nearest rotation sample was within `maxAllowedDeltaNanos`; [pair] is safe to use. */
    data class Paired(val pair: FrameRotationPair) : FrameRotationPairingResult

    /** [RotationSampleHistory] held no samples at all when this frame was analyzed. */
    data class NoSamples(
        val frameTimestampNanos: Long,
    ) : FrameRotationPairingResult

    /**
     * A nearest sample existed, but its absolute delta from the frame exceeded
     * `maxAllowedDeltaNanos` (while staying at or below `clockMismatchThresholdNanos`) — a plausible
     * same-clock-base delta that is simply too old/new to pair. The frame remains unpaired.
     */
    data class OutsideTolerance(
        val frameTimestampNanos: Long,
        val nearestRotationTimestampNanos: Long,
        val deltaNanos: Long,
        val maxAllowedDeltaNanos: Long,
    ) : FrameRotationPairingResult

    /**
     * The nearest sample's absolute delta exceeded `clockMismatchThresholdNanos` — a delta this large
     * cannot plausibly arise from scheduling jitter between two streams sharing a real time base.
     * This is a diagnostic *inference*, not proof the clocks differ; see [TimestampCompatibility].
     */
    data class ClockMismatchSuspected(
        val frameTimestampNanos: Long,
        val nearestRotationTimestampNanos: Long,
        val deltaNanos: Long,
    ) : FrameRotationPairingResult
}

/**
 * Selects the sample in [samples] nearest in time to [frame], then classifies the result against
 * [maxAllowedDeltaNanos] and [clockMismatchThresholdNanos] (CAM-1d). Pure, deterministic, and does
 * not retain [samples] beyond this call — no interpolation, no wall-clock conversion.
 *
 * Selection: minimum `abs(frame.timestampNanos - sample.timestampNanos)` using overflow-safe
 * arithmetic. Ties (equal absolute delta on both sides of the frame) are broken deterministically by
 * preferring the **earlier** sample — i.e. the smaller `timestampNanos` — regardless of [samples]'
 * iteration order.
 *
 * Classification, using that nearest sample's absolute delta `d`:
 *  - `d <= maxAllowedDeltaNanos` → [FrameRotationPairingResult.Paired]
 *  - `d > clockMismatchThresholdNanos` → [FrameRotationPairingResult.ClockMismatchSuspected]
 *  - otherwise → [FrameRotationPairingResult.OutsideTolerance]
 *
 * so a mismatch-scale delta is never misreported as an ordinary tolerance rejection.
 *
 * @throws IllegalArgumentException if either threshold is negative, or if
 *   `clockMismatchThresholdNanos < maxAllowedDeltaNanos`.
 */
fun pairFrameToNearestRotation(
    frame: CameraFrameMetadata,
    samples: List<TimedRotationSample>,
    maxAllowedDeltaNanos: Long,
    clockMismatchThresholdNanos: Long,
): FrameRotationPairingResult {
    require(maxAllowedDeltaNanos >= 0L) {
        "maxAllowedDeltaNanos must be non-negative; was $maxAllowedDeltaNanos"
    }
    require(clockMismatchThresholdNanos >= maxAllowedDeltaNanos) {
        "clockMismatchThresholdNanos ($clockMismatchThresholdNanos) must be >= " +
            "maxAllowedDeltaNanos ($maxAllowedDeltaNanos)"
    }

    if (samples.isEmpty()) {
        return FrameRotationPairingResult.NoSamples(frameTimestampNanos = frame.timestampNanos)
    }

    var nearest = samples[0]
    var nearestDelta = frame.timestampNanos.overflowSafeMinus(nearest.timestampNanos)
    var nearestAbsDelta = overflowSafeAbsNanos(nearestDelta)

    for (index in 1 until samples.size) {
        val candidate = samples[index]
        val delta = frame.timestampNanos.overflowSafeMinus(candidate.timestampNanos)
        val absDelta = overflowSafeAbsNanos(delta)
        val isBetter =
            absDelta < nearestAbsDelta ||
                (absDelta == nearestAbsDelta && candidate.timestampNanos < nearest.timestampNanos)
        if (isBetter) {
            nearest = candidate
            nearestAbsDelta = absDelta
            nearestDelta = delta
        }
    }

    return when {
        nearestAbsDelta <= maxAllowedDeltaNanos ->
            FrameRotationPairingResult.Paired(
                FrameRotationPair(frame = frame, rotation = nearest, deltaNanos = nearestDelta),
            )

        nearestAbsDelta > clockMismatchThresholdNanos ->
            FrameRotationPairingResult.ClockMismatchSuspected(
                frameTimestampNanos = frame.timestampNanos,
                nearestRotationTimestampNanos = nearest.timestampNanos,
                deltaNanos = nearestDelta,
            )

        else ->
            FrameRotationPairingResult.OutsideTolerance(
                frameTimestampNanos = frame.timestampNanos,
                nearestRotationTimestampNanos = nearest.timestampNanos,
                deltaNanos = nearestDelta,
                maxAllowedDeltaNanos = maxAllowedDeltaNanos,
            )
    }
}

/**
 * `a - b`, saturating to [Long.MAX_VALUE]/[Long.MIN_VALUE] instead of wrapping on overflow. Both
 * operands are expected to be non-negative timestamps (enforced by [TimedRotationSample] and
 * [CameraFrameMetadata]), for which plain subtraction can never actually overflow — this exists as
 * an explicit, tested guarantee rather than relying on that invariant holding everywhere forever.
 */
internal fun Long.overflowSafeMinus(other: Long): Long = overflowSafeDeltaNanos(this, other)

internal fun overflowSafeDeltaNanos(
    a: Long,
    b: Long,
): Long {
    val diff = a - b
    // Standard overflow check: overflow occurred iff a and b have different signs AND the result's
    // sign differs from a's sign.
    val overflowed = ((a xor b) and (a xor diff)) < 0
    return if (overflowed) {
        if (a > b) Long.MAX_VALUE else Long.MIN_VALUE
    } else {
        diff
    }
}

/** `abs(value)`, saturating to [Long.MAX_VALUE] for `value == Long.MIN_VALUE` instead of overflowing. */
internal fun overflowSafeAbsNanos(value: Long): Long = if (value == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(value)
