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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * CAM-1d: pairs each camera-frame metadata sample with the nearest sample in a bounded rotation
 * history, and publishes only the latest pairing result and diagnostic debug state — never a queue
 * of every pair. This is instrumentation/synchronization-contract work only: nothing here feeds
 * rendering or star matching (see `docs/camera_coordinate_calibration_contract.md` §4).
 *
 * Callback-driven ([onRotationSample] fed from the sensor listener in `RotationFrame.kt`,
 * [onCameraFrame] fed from [collectCameraFramesForTimestampSync] observing CAM-1c's
 * `CameraFrameMetadataProvider.latest]) — this class owns no coroutine/`Job` of its own, so there is
 * nothing to leak; the owning composition's lifecycle (its `LaunchedEffect`/`DisposableEffect`)
 * governs how long calls keep arriving, and [reset] must be called when that session ends.
 *
 * [onRotationSample] and [onCameraFrame] may be invoked from different threads (the sensor listener
 * thread and whichever thread collects the metadata flow) — both [RotationSampleHistory] and
 * [TimestampSyncDiagnostics] are internally lock-guarded, so no additional synchronization is needed
 * here beyond the plain volatile/atomic bookkeeping for one-shot log guards below.
 */
class CameraTimestampSynchronizer(
    historyCapacity: Int = TimestampSyncConfig.ROTATION_HISTORY_CAPACITY,
    private val maxAllowedDeltaNanos: Long = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
    private val clockMismatchThresholdNanos: Long = TimestampSyncConfig.CLOCK_MISMATCH_THRESHOLD_NANOS,
) {
    private val history = RotationSampleHistory(capacity = historyCapacity)
    private val diagnostics = TimestampSyncDiagnostics()

    private val _latestResult = MutableStateFlow<FrameRotationPairingResult?>(null)
    val latestResult: StateFlow<FrameRotationPairingResult?> = _latestResult.asStateFlow()

    private val processedFrameCount = AtomicLong(0L)
    private val loggedSessionStarted = AtomicBoolean(false)
    private val loggedFirstPair = AtomicBoolean(false)
    private val loggedNoSamplesUnavailable = AtomicBoolean(false)

    @Volatile
    private var lastKnownCompatibility = TimestampCompatibility.UNKNOWN

    /** Feeds one production rotation sample (from `rememberRotationFrame`'s sensor listener) into the bounded history. */
    fun onRotationSample(sample: TimedRotationSample) {
        history.add(sample)
    }

    /** Feeds one camera-frame metadata sample: pairs it, updates diagnostics, and publishes the latest result. */
    fun onCameraFrame(frame: CameraFrameMetadata) {
        if (loggedSessionStarted.compareAndSet(false, true)) {
            MobileLog.timestampSyncSessionStarted()
        }

        val result =
            pairFrameToNearestRotation(
                frame = frame,
                samples = history.snapshot(),
                maxAllowedDeltaNanos = maxAllowedDeltaNanos,
                clockMismatchThresholdNanos = clockMismatchThresholdNanos,
            )
        val state = diagnostics.record(result)
        _latestResult.value = result

        logResult(result, state)
    }

    /** Debug snapshot for a minimal readout — no pixels, only counters/deltas/compatibility. */
    fun debugState(): TimestampSyncDebugState = diagnostics.snapshot()

    /**
     * Clears the bounded rotation history, resets diagnostics, and clears the published result.
     * Callers must invoke this when the owning AR session ends, so no stale rotation sample from a
     * previous session can pair with the first camera frame of a new one (CAM-1d §8).
     */
    fun reset() {
        history.clear()
        diagnostics.reset()
        _latestResult.value = null
        processedFrameCount.set(0L)
        loggedSessionStarted.set(false)
        loggedFirstPair.set(false)
        loggedNoSamplesUnavailable.set(false)
        lastKnownCompatibility = TimestampCompatibility.UNKNOWN
    }

    private fun logResult(
        result: FrameRotationPairingResult,
        state: TimestampSyncDebugState,
    ) {
        when (result) {
            is FrameRotationPairingResult.Paired -> {
                if (loggedFirstPair.compareAndSet(false, true)) {
                    MobileLog.timestampSyncFirstPair(deltaMillis = result.pair.deltaNanos / NANOS_PER_MILLI)
                }
            }

            is FrameRotationPairingResult.NoSamples -> {
                if (loggedNoSamplesUnavailable.compareAndSet(false, true)) {
                    MobileLog.timestampSyncUnavailableNoSamples()
                }
            }

            is FrameRotationPairingResult.OutsideTolerance,
            is FrameRotationPairingResult.ClockMismatchSuspected,
            -> Unit
        }

        val previousCompatibility = lastKnownCompatibility
        lastKnownCompatibility = state.compatibility
        if (previousCompatibility != TimestampCompatibility.MISMATCH_SUSPECTED &&
            state.compatibility == TimestampCompatibility.MISMATCH_SUSPECTED
        ) {
            MobileLog.timestampSyncClockMismatchSuspected(deltaMillis = (state.latestDeltaNanos ?: 0L) / NANOS_PER_MILLI)
        }

        val processed = processedFrameCount.incrementAndGet()
        if (processed % SYNC_SUMMARY_LOG_INTERVAL == 0L) {
            MobileLog.timestampSyncSummary(
                deltaMillis = state.latestDeltaNanos?.let { it / NANOS_PER_MILLI },
                pairedCount = state.pairedFrameCount,
                rejectedCount = state.rejectedFrameCount,
                compatibility = state.compatibility.name,
            )
        }
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** Throttle: log a pairing summary every 30th processed camera frame. */
        const val SYNC_SUMMARY_LOG_INTERVAL = 30L
    }
}
