package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.FrameRotationPairingResult
import dev.pointtosky.core.astro.projection.camera.RotationSampleHistory
import dev.pointtosky.core.astro.projection.camera.TimedRotationSample
import dev.pointtosky.core.astro.projection.camera.TimestampCompatibility
import dev.pointtosky.core.astro.projection.camera.TimestampSyncConfig
import dev.pointtosky.core.astro.projection.camera.TimestampSyncDebugState
import dev.pointtosky.core.astro.projection.camera.TimestampSyncDiagnostics
import dev.pointtosky.core.astro.projection.camera.pairFrameToNearestRotation
import dev.pointtosky.mobile.logging.MobileLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CAM-1d: pairs each camera-frame metadata sample with the nearest sample in a bounded rotation
 * history, and publishes only the latest pairing result and diagnostic debug state — never a queue
 * of every pair. This is instrumentation/synchronization-contract work only: nothing here feeds
 * rendering or star matching (see `docs/camera_coordinate_calibration_contract.md` §4).
 *
 * Callback-driven: [onRotationSample] is fed from `rememberRotationFrame`'s `onRotationSample`
 * parameter (the sensor listener in `RotationFrame.kt`); [onCameraFrame] is fed from
 * `CameraPreview`'s `onFrameMetadata` parameter (the analyzer's metadata sink in
 * `CameraPreview.kt`). This class owns no coroutine/`Job` of its own, so there is nothing to leak —
 * the owning composition's lifecycle governs how long calls keep arriving, and [dispose] must be
 * called exactly once, when that AR session ends.
 *
 * **Ownership is terminal, not reusable.** One instance belongs to exactly one `ArScreen`
 * composition (`remember`ed there). [dispose] is a one-way transition, not a reusable
 * "clear and keep going": once disposed, [onRotationSample] and [onCameraFrame] are permanently
 * no-ops. A new AR session gets a new [CameraTimestampSynchronizer] instance instead of calling
 * [dispose] and continuing to use the old one.
 *
 * **Disposal is serialized with session activity through one [lock]**, not independent atomics.
 * [onRotationSample], [onCameraFrame], and [dispose] each acquire [lock] for their entire
 * check-disposed → read/mutate-state transition, so there is no window in which a callback already
 * "in flight" can record diagnostics or publish a result after [dispose] has completed, and no
 * window in which [dispose] can observe a half-updated state. Whichever of a given callback and a
 * concurrent [dispose] call acquires [lock] first entirely determines the outcome: either the
 * callback's effects land and [dispose] then clears them, or [dispose] lands first and the callback
 * observes [disposed] and no-ops — there is no third interleaving. See
 * `CameraTimestampSynchronizerTest`'s race tests.
 *
 * `MobileLog` calls are never made while holding [lock]: [onCameraFrame] computes an immutable
 * [LogPlan] under the lock, releases it, and only then issues the actual log calls from that
 * captured, immutable snapshot.
 */
class CameraTimestampSynchronizer(
    historyCapacity: Int = TimestampSyncConfig.ROTATION_HISTORY_CAPACITY,
    private val maxAllowedDeltaNanos: Long = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
    private val clockMismatchThresholdNanos: Long = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
) {
    init {
        require(historyCapacity > 0) { "historyCapacity must be positive; was $historyCapacity" }
        require(maxAllowedDeltaNanos >= 0L) { "maxAllowedDeltaNanos must be non-negative; was $maxAllowedDeltaNanos" }
        require(clockMismatchThresholdNanos >= maxAllowedDeltaNanos) {
            "clockMismatchThresholdNanos ($clockMismatchThresholdNanos) must be >= " +
                "maxAllowedDeltaNanos ($maxAllowedDeltaNanos)"
        }
    }

    private val history = RotationSampleHistory(capacity = historyCapacity)
    private val diagnostics = TimestampSyncDiagnostics()

    private val _latestResult = MutableStateFlow<FrameRotationPairingResult?>(null)
    val latestResult: StateFlow<FrameRotationPairingResult?> = _latestResult.asStateFlow()

    // Single lock serializing session activity (onRotationSample/onCameraFrame) with the disposed
    // transition - see the class kdoc. Every field below is read/written only while holding it.
    private val lock = Any()
    private var disposed = false
    private var processedFrameCount = 0L
    private var loggedSessionStarted = false
    private var loggedFirstPair = false
    private var loggedNoSamplesUnavailable = false
    private var lastKnownCompatibility = TimestampCompatibility.UNKNOWN

    /** Feeds one production rotation sample into the bounded history. No-op after [dispose]. */
    fun onRotationSample(sample: TimedRotationSample) {
        synchronized(lock) {
            if (disposed) return
            history.add(sample)
        }
    }

    /**
     * Feeds one camera-frame metadata sample: pairs it, updates diagnostics, publishes the latest
     * result, and returns that same result — CAM-1f's
     * `dev.pointtosky.mobile.ar.camera.CameraSessionGeometryProvider` uses the return value to hand
     * a frame and *the pairing computed for that exact frame* to `onPairedFrame` atomically,
     * instead of separately reading two independently-"latest" sources that could belong to
     * different frames. No-op after [dispose] — does not even publish or return a `NoSamples`
     * result.
     *
     * @return the pairing result for [frame], or `null` if this synchronizer is already disposed.
     */
    fun onCameraFrame(frame: CameraFrameMetadata): FrameRotationPairingResult? {
        val (result, logPlan) =
            synchronized(lock) {
                if (disposed) return null
                val result =
                    pairFrameToNearestRotation(
                        frame = frame,
                        samples = history.snapshot(),
                        maxAllowedDeltaNanos = maxAllowedDeltaNanos,
                        clockMismatchThresholdNanos = clockMismatchThresholdNanos,
                    )
                val state = diagnostics.record(result)
                _latestResult.value = result
                result to planLog(result, state)
            }
        runLog(logPlan)
        return result
    }

    /** Debug snapshot for a minimal readout — no pixels, only counters/deltas/compatibility. */
    fun debugState(): TimestampSyncDebugState = diagnostics.snapshot()

    /**
     * Terminal, idempotent: after this call, [onRotationSample] and [onCameraFrame] are permanently
     * no-ops, the bounded history and diagnostics are cleared, and [latestResult] is `null`. Safe to
     * call more than once — the second and later calls are no-ops. Callers must invoke this exactly
     * when the owning AR session ends (e.g. `ArScreen`'s `DisposableEffect(Unit)`'s `onDispose`), so
     * no rotation sample or camera frame from that session can be recorded or published afterward —
     * including one already "in flight" on another thread when this is called; see the class kdoc.
     */
    fun dispose() {
        synchronized(lock) {
            if (disposed) return
            disposed = true
            history.clear()
            diagnostics.reset()
            _latestResult.value = null
            processedFrameCount = 0L
            loggedSessionStarted = false
            loggedFirstPair = false
            loggedNoSamplesUnavailable = false
            lastKnownCompatibility = TimestampCompatibility.UNKNOWN
        }
    }

    /** Must be called while holding [lock]. Computes what to log without calling [MobileLog] itself. */
    private fun planLog(
        result: FrameRotationPairingResult,
        state: TimestampSyncDebugState,
    ): LogPlan {
        val sessionStarted = !loggedSessionStarted
        if (sessionStarted) loggedSessionStarted = true

        var firstPairDeltaMillis: Long? = null
        var noSamplesUnavailable = false
        when (result) {
            is FrameRotationPairingResult.Paired -> {
                if (!loggedFirstPair) {
                    loggedFirstPair = true
                    firstPairDeltaMillis = result.pair.deltaNanos / NANOS_PER_MILLI
                }
            }

            is FrameRotationPairingResult.NoSamples -> {
                if (!loggedNoSamplesUnavailable) {
                    loggedNoSamplesUnavailable = true
                    noSamplesUnavailable = true
                }
            }

            is FrameRotationPairingResult.OutsideTolerance,
            is FrameRotationPairingResult.ClockMismatchSuspected,
            -> Unit
        }

        val previousCompatibility = lastKnownCompatibility
        lastKnownCompatibility = state.compatibility
        val mismatchSuspectedDeltaMillis =
            if (previousCompatibility != TimestampCompatibility.MISMATCH_SUSPECTED &&
                state.compatibility == TimestampCompatibility.MISMATCH_SUSPECTED
            ) {
                (state.latestDeltaNanos ?: 0L) / NANOS_PER_MILLI
            } else {
                null
            }

        processedFrameCount++
        val summary =
            if (processedFrameCount % SYNC_SUMMARY_LOG_INTERVAL == 0L) {
                SummaryLog(
                    deltaMillis = state.latestDeltaNanos?.let { it / NANOS_PER_MILLI },
                    pairedCount = state.pairedFrameCount,
                    rejectedCount = state.rejectedFrameCount,
                    compatibility = state.compatibility.name,
                )
            } else {
                null
            }

        return LogPlan(
            sessionStarted = sessionStarted,
            firstPairDeltaMillis = firstPairDeltaMillis,
            noSamplesUnavailable = noSamplesUnavailable,
            mismatchSuspectedDeltaMillis = mismatchSuspectedDeltaMillis,
            summary = summary,
        )
    }

    /** Must be called without holding [lock] — issues the actual (immutable, already-decided) log calls. */
    private fun runLog(logPlan: LogPlan) {
        if (logPlan.sessionStarted) {
            MobileLog.timestampSyncSessionStarted()
        }
        logPlan.firstPairDeltaMillis?.let { MobileLog.timestampSyncFirstPair(deltaMillis = it) }
        if (logPlan.noSamplesUnavailable) {
            MobileLog.timestampSyncUnavailableNoSamples()
        }
        logPlan.mismatchSuspectedDeltaMillis?.let { MobileLog.timestampSyncClockMismatchSuspected(deltaMillis = it) }
        logPlan.summary?.let { summary ->
            MobileLog.timestampSyncSummary(
                deltaMillis = summary.deltaMillis,
                pairedCount = summary.pairedCount,
                rejectedCount = summary.rejectedCount,
                compatibility = summary.compatibility,
            )
        }
    }

    /** Immutable plan of which [MobileLog] events (if any) [onCameraFrame] should fire, decided under [lock]. */
    private class LogPlan(
        val sessionStarted: Boolean,
        val firstPairDeltaMillis: Long?,
        val noSamplesUnavailable: Boolean,
        val mismatchSuspectedDeltaMillis: Long?,
        val summary: SummaryLog?,
    )

    private class SummaryLog(
        val deltaMillis: Long?,
        val pairedCount: Long,
        val rejectedCount: Long,
        val compatibility: String,
    )

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** Throttle: log a pairing summary every 30th processed camera frame. */
        const val SYNC_SUMMARY_LOG_INTERVAL = 30L
    }
}
