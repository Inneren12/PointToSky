package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.skylog.SkyExposureSample

/**
 * SKY-1 (`internalDebug`-only): joins an analyzed frame's pixels to the `CaptureResult` that produced
 * them, keyed on the exact `SENSOR_TIMESTAMP`.
 *
 * ## Why a join and not a lookup
 * `ImageProxy` arrives on the analysis executor; `CaptureResult` arrives on the camera callback
 * thread. Neither order is guaranteed, and on a long exposure the gap is large. A one-shot
 * "look up the exposure when the image arrives" therefore loses every pair where the result is
 * merely *late* — and loses it silently, recording the frame with `exposure = null` as though the
 * device had never reported one. For a dataset whose entire premise is a known manual exposure, that
 * is the worst possible failure: the log looks complete and is not.
 *
 * This join holds both sides. Whichever arrives second completes the pair. Nothing is ever matched
 * approximately: `CaptureResult.SENSOR_TIMESTAMP` is the same value as `ImageProxy.imageInfo.timestamp`
 * for the same frame, so a match is exact equality or it is not a match.
 *
 * ## Bounded, and bounded on purpose
 * A pending frame holds its whole luma plane, so an unbounded join is an unbounded pixel buffer.
 * Both sides are capped at [capacity] entries (oldest evicted first) and additionally aged out by
 * [maxWaitNanos] measured **against the sensor clock itself**, not a wall clock: the newest sensor
 * timestamp seen from either side is "now", so the ageing is deterministic, testable, and unaffected
 * by how long the JVM happened to pause.
 *
 * Worst-case retained pixel memory is `capacity * (rowStride * height)` bytes — at the default
 * capacity and 1280x720, about 5.6 MiB.
 *
 * ## Not thread-safe, by design
 * One instance is owned by one capture session and touched only from that session's single analysis
 * executor thread. The camera callback does not call in directly; it posts its sample to that same
 * executor, so every offer and every delivery is serialized without a lock. See
 * [SkySessionCameraPreview].
 */
internal class SkyExposureJoin(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxWaitNanos: Long = DEFAULT_MAX_WAIT_NANOS,
) {
    init {
        require(capacity > 0) { "capacity must be positive; was $capacity" }
        require(maxWaitNanos > 0L) { "maxWaitNanos must be positive; was $maxWaitNanos" }
    }

    // LinkedHashMap in insertion order: eviction is "drop the oldest", which is the head.
    private val pendingFrames = LinkedHashMap<Long, SkyAnalyzedFrame>()
    private val pendingExposures = LinkedHashMap<Long, SkyExposureSample>()

    /** The newest sensor timestamp seen from either side — this join's notion of "now". */
    private var latestObservedTimestampNanos: Long? = null

    val pendingFrameCount: Int get() = pendingFrames.size

    val pendingExposureCount: Int get() = pendingExposures.size

    /**
     * Offers one analyzed frame.
     *
     * A duplicate timestamp is **refused, first-wins**: the already-held frame is the one whose pixels
     * arrived first and may already be half-joined, and silently replacing it would swap the pixels
     * under a pair that is about to be recorded. A second frame claiming the same sensor timestamp is
     * anomalous in the first place — Camera2 does not reuse them.
     */
    fun offerFrame(frame: SkyAnalyzedFrame): SkyJoinResult {
        val timestamp = frame.metadata.timestampNanos
        observe(timestamp)
        val expired = expireStale()

        if (pendingFrames.containsKey(timestamp)) {
            return SkyJoinResult(
                dropped = expired + SkyJoinDrop(timestamp, SkyJoinDropReason.FRAME_DUPLICATE_TIMESTAMP),
            )
        }

        val exposure = pendingExposures.remove(timestamp)
        if (exposure != null) {
            return SkyJoinResult(matched = SkyJoinedFrame(frame, exposure), dropped = expired)
        }

        pendingFrames[timestamp] = frame
        return SkyJoinResult(dropped = expired + evictFramesOverCapacity())
    }

    /**
     * Offers one `CaptureResult`-derived sample.
     *
     * A sample with no `SENSOR_TIMESTAMP` cannot be attributed to any frame and is dropped as
     * [SkyJoinDropReason.EXPOSURE_UNKEYED] rather than guessed at — see [SkyExposureJoin]'s KDoc.
     */
    fun offerExposure(sample: SkyExposureSample): SkyJoinResult {
        val timestamp =
            sample.sensorTimestampNanos
                ?: return SkyJoinResult(dropped = listOf(SkyJoinDrop(null, SkyJoinDropReason.EXPOSURE_UNKEYED)))
        observe(timestamp)
        val expired = expireStale()

        if (pendingExposures.containsKey(timestamp)) {
            return SkyJoinResult(
                dropped =
                    expired + SkyJoinDrop(timestamp, SkyJoinDropReason.EXPOSURE_DUPLICATE_TIMESTAMP),
            )
        }

        val frame = pendingFrames.remove(timestamp)
        if (frame != null) {
            return SkyJoinResult(matched = SkyJoinedFrame(frame, sample), dropped = expired)
        }

        pendingExposures[timestamp] = sample
        return SkyJoinResult(dropped = expired + evictExposuresOverCapacity())
    }

    /**
     * Releases everything still waiting, reporting it as [SkyJoinDropReason.PENDING_AT_STOP]. Called
     * when a capture session ends so a HUD can state how many frames were never completed rather than
     * leaving them silently unaccounted for.
     */
    fun drain(): List<SkyJoinDrop> {
        val drops =
            pendingFrames.keys.map { SkyJoinDrop(it, SkyJoinDropReason.PENDING_AT_STOP) } +
                pendingExposures.keys.map { SkyJoinDrop(it, SkyJoinDropReason.PENDING_AT_STOP) }
        pendingFrames.clear()
        pendingExposures.clear()
        latestObservedTimestampNanos = null
        return drops
    }

    private fun observe(timestampNanos: Long) {
        val latest = latestObservedTimestampNanos
        if (latest == null || timestampNanos > latest) latestObservedTimestampNanos = timestampNanos
    }

    private fun expireStale(): List<SkyJoinDrop> {
        val now = latestObservedTimestampNanos ?: return emptyList()
        val cutoff = now - maxWaitNanos
        val drops = mutableListOf<SkyJoinDrop>()
        pendingFrames.entries.removeAll { (timestamp, _) ->
            (timestamp < cutoff).also { if (it) drops += SkyJoinDrop(timestamp, SkyJoinDropReason.FRAME_TIMED_OUT) }
        }
        pendingExposures.entries.removeAll { (timestamp, _) ->
            (timestamp < cutoff).also { if (it) drops += SkyJoinDrop(timestamp, SkyJoinDropReason.EXPOSURE_TIMED_OUT) }
        }
        return drops
    }

    private fun evictFramesOverCapacity(): List<SkyJoinDrop> =
        evictOverCapacity(pendingFrames, SkyJoinDropReason.FRAME_EVICTED)

    private fun evictExposuresOverCapacity(): List<SkyJoinDrop> =
        evictOverCapacity(pendingExposures, SkyJoinDropReason.EXPOSURE_EVICTED)

    private fun <V> evictOverCapacity(
        pending: LinkedHashMap<Long, V>,
        reason: SkyJoinDropReason,
    ): List<SkyJoinDrop> {
        if (pending.size <= capacity) return emptyList()
        val drops = mutableListOf<SkyJoinDrop>()
        val iterator = pending.keys.iterator()
        while (pending.size > capacity && iterator.hasNext()) {
            val oldest = iterator.next()
            iterator.remove()
            drops += SkyJoinDrop(oldest, reason)
        }
        return drops
    }

    internal companion object {
        /**
         * Deep enough to absorb the analysis pipeline's own latency (CameraX keeps a small number of
         * frames in flight) plus a late result or two; shallow enough that the retained pixel memory
         * stays a few MiB rather than growing with session length.
         */
        const val DEFAULT_CAPACITY = 6

        /**
         * Two seconds of sensor time. Comfortably longer than the longest exposure this screen offers
         * (2 s) plus pipeline latency, so a legitimately slow result still joins; short enough that a
         * result which is never coming releases its frame's pixels promptly.
         */
        const val DEFAULT_MAX_WAIT_NANOS = 4_000_000_000L
    }
}

/** Why one side of the join was released without ever completing a pair. */
internal enum class SkyJoinDropReason {
    /** A frame aged past `maxWaitNanos` with no matching `CaptureResult`. */
    FRAME_TIMED_OUT,

    /** A frame was pushed out by newer frames before its result arrived. */
    FRAME_EVICTED,

    /** A second frame claimed a sensor timestamp already held; the first is kept. */
    FRAME_DUPLICATE_TIMESTAMP,

    /** A `CaptureResult` aged past `maxWaitNanos` with no matching frame (its image was never analyzed). */
    EXPOSURE_TIMED_OUT,

    /** A `CaptureResult` was pushed out by newer results before its frame arrived. */
    EXPOSURE_EVICTED,

    /** A second `CaptureResult` claimed a sensor timestamp already held; the first is kept. */
    EXPOSURE_DUPLICATE_TIMESTAMP,

    /** A `CaptureResult` carried no `SENSOR_TIMESTAMP`, so it cannot belong to any frame. */
    EXPOSURE_UNKEYED,

    /** Still waiting when the session ended. */
    PENDING_AT_STOP,
}

/** One frame and the exposure that provably produced it. */
internal data class SkyJoinedFrame(
    val frame: SkyAnalyzedFrame,
    val exposure: SkyExposureSample,
)

/** @property frameTimestampNanos `null` only for [SkyJoinDropReason.EXPOSURE_UNKEYED]. */
internal data class SkyJoinDrop(
    val frameTimestampNanos: Long?,
    val reason: SkyJoinDropReason,
)

/**
 * What one offer produced: at most one completed pair, plus whatever that offer released.
 *
 * [matched] is at most one because each offer contributes exactly one side, and a side matches at
 * most one counterpart.
 */
internal data class SkyJoinResult(
    val matched: SkyJoinedFrame? = null,
    val dropped: List<SkyJoinDrop> = emptyList(),
)
