package dev.pointtosky.mobile.ar.camera

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
 * ## What is joined (PTS-03)
 * The capture side is one immutable [SkyCaptureResultSnapshot] per `CaptureResult`: SKY-1's
 * [dev.pointtosky.core.astro.projection.camera.skylog.SkyExposureSample] plus the PTS-03 camera-truth
 * fields, read from the same result object in one call (`skyCaptureResultSnapshotOf`). The frame side is
 * any [SkyJoinFrame] — SKY-1's luma-carrying [SkyAnalyzedFrame], or the CAM-2c/PTS-03 frame-content
 * analyzer's metadata-plus-detection frame. The key and the rule are the same for both: exact
 * `SENSOR_TIMESTAMP` equality, never nearest, never "latest result", never frame order.
 *
 * ## Nothing is dropped silently
 * Every offer and every release is counted in [statistics], so a session can state how many analysis
 * frames actually received their own `CaptureResult` ([SkyJoinStatistics.matchedFraction]) — PTS-03 Group A
 * needs exactly that number before any per-frame metadata may be trusted.
 *
 * ## Not thread-safe, by design
 * One instance is owned by one capture session and touched only from that session's single analysis
 * executor thread. The camera callback does not call in directly; it posts its sample to that same
 * executor, so every offer and every delivery is serialized without a lock. See
 * [SkySessionCameraPreview].
 */
internal class SkyExposureJoin<F : SkyJoinFrame>(
    private val capacity: Int = DEFAULT_CAPACITY,
    private val maxWaitNanos: Long = DEFAULT_MAX_WAIT_NANOS,
) {
    init {
        require(capacity > 0) { "capacity must be positive; was $capacity" }
        require(maxWaitNanos > 0L) { "maxWaitNanos must be positive; was $maxWaitNanos" }
    }

    // LinkedHashMap in insertion order: eviction is "drop the oldest", which is the head.
    private val pendingFrames = LinkedHashMap<Long, F>()
    private val pendingExposures = LinkedHashMap<Long, SkyCaptureResultSnapshot>()
    private val counters = SkyJoinCounters()

    /** The newest sensor timestamp seen from either side — this join's notion of "now". */
    private var latestObservedTimestampNanos: Long? = null

    val pendingFrameCount: Int get() = pendingFrames.size

    val pendingExposureCount: Int get() = pendingExposures.size

    /** Everything this join has seen and released since construction. One join serves one bind, so these are per-bind counts; [drain] does not reset them. */
    val statistics: SkyJoinStatistics get() = counters.snapshot()

    /**
     * Offers one analyzed frame.
     *
     * A duplicate timestamp is **refused, first-wins**: the already-held frame is the one whose pixels
     * arrived first and may already be half-joined, and silently replacing it would swap the pixels
     * under a pair that is about to be recorded. A second frame claiming the same sensor timestamp is
     * anomalous in the first place — Camera2 does not reuse them.
     */
    fun offerFrame(frame: F): SkyJoinResult<F> {
        if (isFinalized) return ignoredAfterFinalize()
        counters.analysisFrameCount++
        val timestamp = frame.sensorTimestampNanos
        observe(timestamp)
        val expired = expireStale()

        if (pendingFrames.containsKey(timestamp)) {
            return counted(
                SkyJoinResult(
                    dropped = expired + SkyJoinDrop(timestamp, SkyJoinDropReason.FRAME_DUPLICATE_TIMESTAMP),
                ),
            )
        }

        val captureResult = pendingExposures.remove(timestamp)
        if (captureResult != null) {
            return counted(SkyJoinResult(matched = SkyJoinedFrame(frame, captureResult), dropped = expired))
        }

        pendingFrames[timestamp] = frame
        return counted(SkyJoinResult(dropped = expired + evictFramesOverCapacity()))
    }

    /**
     * Offers one `CaptureResult` snapshot.
     *
     * A snapshot with no `SENSOR_TIMESTAMP` cannot be attributed to any frame and is dropped as
     * [SkyJoinDropReason.EXPOSURE_UNKEYED] rather than guessed at — see [SkyExposureJoin]'s KDoc.
     */
    fun offerExposure(captureResult: SkyCaptureResultSnapshot): SkyJoinResult<F> {
        if (isFinalized) return ignoredAfterFinalize()
        counters.captureResultCount++
        val timestamp =
            captureResult.sensorTimestampNanos
                ?: return counted(SkyJoinResult(dropped = listOf(SkyJoinDrop(null, SkyJoinDropReason.EXPOSURE_UNKEYED))))
        observe(timestamp)
        val expired = expireStale()

        if (pendingExposures.containsKey(timestamp)) {
            return counted(
                SkyJoinResult(
                    dropped =
                        expired + SkyJoinDrop(timestamp, SkyJoinDropReason.EXPOSURE_DUPLICATE_TIMESTAMP),
                ),
            )
        }

        val frame = pendingFrames.remove(timestamp)
        if (frame != null) {
            return counted(SkyJoinResult(matched = SkyJoinedFrame(frame, captureResult), dropped = expired))
        }

        pendingExposures[timestamp] = captureResult
        return counted(SkyJoinResult(dropped = expired + evictExposuresOverCapacity()))
    }

    /**
     * Releases everything still waiting, reporting it as [SkyJoinDropReason.PENDING_AT_STOP]. Called
     * when a capture session ends so a HUD can state how many frames were never completed rather than
     * leaving them silently unaccounted for.
     */
    /** `true` once [finalizeJoin] ran: no further offer is accepted or counted as a frame/result. */
    var isFinalized: Boolean = false
        private set

    /**
     * Ends this join for evidence purposes: stops accepting offers, drains everything still pending into
     * the pending-at-stop counts, and returns the final statistics. Idempotent — a second call drains
     * nothing (nothing can be pending once offers stopped) and returns identical statistics, so pending
     * entries are never counted twice.
     */
    fun finalizeJoin(): SkyJoinStatistics {
        if (!isFinalized) {
            drain()
            isFinalized = true
        }
        return statistics
    }

    private fun ignoredAfterFinalize(): SkyJoinResult<F> {
        counters.offersIgnoredAfterFinalizeCount++
        return SkyJoinResult()
    }

    fun drain(): List<SkyJoinDrop> {
        val drops =
            pendingFrames.keys.map { SkyJoinDrop(it, SkyJoinDropReason.PENDING_AT_STOP) } +
                pendingExposures.keys.map { SkyJoinDrop(it, SkyJoinDropReason.PENDING_AT_STOP) }
        counters.framesPendingAtStopCount += pendingFrames.size
        counters.captureResultsPendingAtStopCount += pendingExposures.size
        pendingFrames.clear()
        pendingExposures.clear()
        latestObservedTimestampNanos = null
        return drops
    }

    private fun counted(result: SkyJoinResult<F>): SkyJoinResult<F> {
        if (result.matched != null) counters.matchedCount++
        result.dropped.forEach { counters.count(it.reason) }
        return result
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

/**
 * The frame side of the join: anything analyzed from one `ImageProxy`, keyed by that proxy's
 * `imageInfo.timestamp` (the same clock and value as `CaptureResult.SENSOR_TIMESTAMP` for the same frame).
 */
internal interface SkyJoinFrame {
    val sensorTimestampNanos: Long
}

/**
 * One frame and the `CaptureResult` that provably produced it. [exposure] is SKY-1's unchanged view of
 * [captureResult]; [captureResult] carries the PTS-03 truth from the very same result object.
 */
internal data class SkyJoinedFrame<F : SkyJoinFrame>(
    val frame: F,
    val captureResult: SkyCaptureResultSnapshot,
) {
    val exposure: dev.pointtosky.core.astro.projection.camera.skylog.SkyExposureSample
        get() = captureResult.exposure
}

/**
 * Match-quality counts for one join (one bind). Every offer and every release is counted exactly once,
 * so `analysisFrameCount == matchedCount + frameTimedOutCount + frameEvictedCount +
 * duplicateFrameTimestampCount + framesPendingAtStopCount + (frames still pending)` always holds, and
 * likewise for the capture side.
 */
internal data class SkyJoinStatistics(
    val analysisFrameCount: Long,
    val captureResultCount: Long,
    val matchedCount: Long,
    val frameTimedOutCount: Long,
    val captureResultTimedOutCount: Long,
    val frameEvictedCount: Long,
    val captureResultEvictedCount: Long,
    val duplicateFrameTimestampCount: Long,
    val duplicateCaptureTimestampCount: Long,
    val unkeyedCaptureResultCount: Long,
    val framesPendingAtStopCount: Long,
    val captureResultsPendingAtStopCount: Long,
    /** Offers that arrived after [SkyExposureJoin.finalizeJoin]; not counted as frames or results. */
    val offersIgnoredAfterFinalizeCount: Long = 0,
) {
    /** Fraction of analysis frames that received their own `CaptureResult`; `null` before any frame. */
    val matchedFraction: Double?
        get() = if (analysisFrameCount == 0L) null else matchedCount.toDouble() / analysisFrameCount.toDouble()

    companion object {
        val EMPTY = SkyJoinStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    }
}

private class SkyJoinCounters {
    var analysisFrameCount = 0L
    var captureResultCount = 0L
    var matchedCount = 0L
    var frameTimedOutCount = 0L
    var captureResultTimedOutCount = 0L
    var frameEvictedCount = 0L
    var captureResultEvictedCount = 0L
    var duplicateFrameTimestampCount = 0L
    var duplicateCaptureTimestampCount = 0L
    var unkeyedCaptureResultCount = 0L
    var framesPendingAtStopCount = 0L
    var captureResultsPendingAtStopCount = 0L
    var offersIgnoredAfterFinalizeCount = 0L

    fun count(reason: SkyJoinDropReason) {
        when (reason) {
            SkyJoinDropReason.FRAME_TIMED_OUT -> frameTimedOutCount++
            SkyJoinDropReason.FRAME_EVICTED -> frameEvictedCount++
            SkyJoinDropReason.FRAME_DUPLICATE_TIMESTAMP -> duplicateFrameTimestampCount++
            SkyJoinDropReason.EXPOSURE_TIMED_OUT -> captureResultTimedOutCount++
            SkyJoinDropReason.EXPOSURE_EVICTED -> captureResultEvictedCount++
            SkyJoinDropReason.EXPOSURE_DUPLICATE_TIMESTAMP -> duplicateCaptureTimestampCount++
            SkyJoinDropReason.EXPOSURE_UNKEYED -> unkeyedCaptureResultCount++
            // Counted per side in drain(), where the side is still known.
            SkyJoinDropReason.PENDING_AT_STOP -> Unit
        }
    }

    fun snapshot(): SkyJoinStatistics =
        SkyJoinStatistics(
            analysisFrameCount = analysisFrameCount,
            captureResultCount = captureResultCount,
            matchedCount = matchedCount,
            frameTimedOutCount = frameTimedOutCount,
            captureResultTimedOutCount = captureResultTimedOutCount,
            frameEvictedCount = frameEvictedCount,
            captureResultEvictedCount = captureResultEvictedCount,
            duplicateFrameTimestampCount = duplicateFrameTimestampCount,
            duplicateCaptureTimestampCount = duplicateCaptureTimestampCount,
            unkeyedCaptureResultCount = unkeyedCaptureResultCount,
            framesPendingAtStopCount = framesPendingAtStopCount,
            captureResultsPendingAtStopCount = captureResultsPendingAtStopCount,
            offersIgnoredAfterFinalizeCount = offersIgnoredAfterFinalizeCount,
        )
}

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
internal data class SkyJoinResult<F : SkyJoinFrame>(
    val matched: SkyJoinedFrame<F>? = null,
    val dropped: List<SkyJoinDrop> = emptyList(),
)
