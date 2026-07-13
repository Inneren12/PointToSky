package dev.pointtosky.core.astro.projection.camera

/**
 * Bounded, thread-safe, timestamp-ordered history of the most recent [TimedRotationSample]s
 * (CAM-1d). Backed by a plain array-backed buffer capped at [capacity] — never an unbounded
 * list/queue — so memory use stays bounded no matter how long a session runs. A ring buffer would
 * work equally well here; a sorted buffer was chosen so out-of-order arrival (rare for a live sensor
 * stream, but not assumed away) is handled correctly without extra bookkeeping.
 *
 * Samples are kept sorted ascending by [TimedRotationSample.timestampNanos] at all times, even if
 * [add] is called out of order. When [capacity] is exceeded, the sample(s) with the *smallest*
 * timestamp are evicted first — i.e. this always retains the most recent [capacity] samples by
 * timestamp, not simply the most recently inserted.
 *
 * Duplicate timestamps are preserved (never deduplicated) and ordered by arrival among themselves:
 * a new sample added with the same timestamp as an existing one is placed after it. Consequently,
 * [nearest] resolves an exact-timestamp match against multiple duplicates to the most recently added
 * one — see [nearest]'s kdoc.
 *
 * [TimedRotationSample.rotationMatrix] arrays are defensively copied on both [add] (so a caller
 * mutating the array it passed in cannot corrupt history state) and [nearest]/[snapshot] (so a
 * caller mutating a returned array cannot corrupt history state). No Android object of any kind is
 * retained — a plain array of numbers is the entire off-heap footprint.
 *
 * All public methods acquire a single internal lock for the duration of the call; camera-frame
 * processing and sensor-event delivery may safely call into the same [RotationSampleHistory]
 * instance concurrently from different threads.
 */
class RotationSampleHistory(
    private val capacity: Int = TimestampSyncConfig.ROTATION_HISTORY_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be positive; was $capacity" }
    }

    private val lock = Any()
    private val samples = ArrayDeque<TimedRotationSample>()

    /** Inserts a defensive copy of [sample] in timestamp order, then evicts down to [capacity]. */
    fun add(sample: TimedRotationSample) {
        val copy = sample.copy(rotationMatrix = sample.rotationMatrix.copyOf())
        synchronized(lock) {
            samples.add(upperBound(sample.timestampNanos), copy)
            while (samples.size > capacity) {
                samples.removeFirst()
            }
        }
    }

    /**
     * Returns a defensive copy of the retained sample whose timestamp is nearest [timestampNanos],
     * or `null` if the history is empty.
     *
     * Ties — an earlier and a later sample equidistant from [timestampNanos] — are broken by
     * preferring the **earlier** sample, matching [pairFrameToNearestRotation]'s tie-break rule. An
     * exact match against multiple same-timestamp duplicates instead resolves to the most recently
     * [add]-ed of those duplicates (delta is `0` for all of them, so "earlier" does not distinguish
     * between them; arrival order does).
     */
    fun nearest(timestampNanos: Long): TimedRotationSample? {
        synchronized(lock) {
            if (samples.isEmpty()) return null

            val insertionIndex = upperBound(timestampNanos)
            val after = samples.getOrNull(insertionIndex)
            val before = samples.getOrNull(insertionIndex - 1)

            val best =
                when {
                    after == null -> before
                    before == null -> after
                    else -> {
                        val beforeAbsDelta = overflowSafeAbsNanos(timestampNanos.overflowSafeMinus(before.timestampNanos))
                        val afterAbsDelta = overflowSafeAbsNanos(timestampNanos.overflowSafeMinus(after.timestampNanos))
                        if (afterAbsDelta < beforeAbsDelta) after else before
                    }
                }

            return best?.let { it.copy(rotationMatrix = it.rotationMatrix.copyOf()) }
        }
    }

    /** Defensive-copy snapshot of every retained sample, oldest timestamp first. */
    fun snapshot(): List<TimedRotationSample> =
        synchronized(lock) {
            samples.map { it.copy(rotationMatrix = it.rotationMatrix.copyOf()) }
        }

    /** Discards all retained samples. Callers must invoke this when a session ends (CAM-1d §8). */
    fun clear() {
        synchronized(lock) {
            samples.clear()
        }
    }

    /**
     * First index in [samples] whose timestamp is strictly greater than [timestampNanos] — i.e. the
     * insertion point that places a new same-timestamp element after all existing equal elements.
     * Must be called while holding [lock].
     */
    private fun upperBound(timestampNanos: Long): Int {
        var lo = 0
        var hi = samples.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (samples[mid].timestampNanos <= timestampNanos) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }
        return lo
    }
}
