package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.CameraGeometryQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryStatus
import dev.pointtosky.core.astro.projection.camera.FrameRotationPairingResult
import dev.pointtosky.core.astro.projection.camera.IntrinsicsUnavailableReason
import dev.pointtosky.core.astro.projection.camera.PixelSize
import dev.pointtosky.core.astro.projection.camera.createCameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.quality
import dev.pointtosky.core.astro.projection.camera.status
import dev.pointtosky.mobile.logging.MobileLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bounded, cheap, session-scoped snapshot of [CameraSessionGeometryProvider]'s activity (CAM-1f).
 * Never grows with session length — every field is a running count or the single latest value; no
 * historical bundle list.
 */
data class CameraSessionGeometryDebugState(
    val observedFrameCount: Long,
    val readyBundleCount: Long,
    val rejectedBundleCount: Long,
    val latestStatus: CameraSessionGeometryStatus,
    val latestQuality: CameraGeometryQuality?,
    val latestPairDeltaNanos: Long?,
)

/**
 * Session-scoped production owner combining CAM-1c frame metadata, the CAM-1d frame/rotation
 * pairing computed for that *same* frame, the AR overlay viewport, and a once-per-session CAM-1b
 * intrinsics resolution into the latest [CameraSessionGeometryResult] (CAM-1f). Publishes
 * latest-value-only via [state] — never a queue of historical bundles. Nothing in `:mobile`
 * consumes [state] yet; this PR wires the owner only, not a renderer or matcher.
 *
 * **Frame/pairing coherence.** [onPairedFrame] takes a frame and the pairing result computed for
 * that *exact* frame together, in one call — never combined from two independently "latest"
 * sources. `dev.pointtosky.mobile.ar.camera.CameraTimestampSynchronizer.onCameraFrame` returns the
 * [FrameRotationPairingResult] it just computed for the frame it was given (CAM-1f addition), so a
 * caller (`ArScreen`) can hand both to [onPairedFrame] atomically. The pure factory
 * [createCameraSessionGeometry] this class delegates to independently verifies the pairing's frame
 * timestamp matches the supplied frame, rejecting any mismatch as
 * [CameraSessionGeometryResult.GeometryRejected] rather than trusting the caller.
 *
 * **A published bundle always uses:** the exact frame from the most recent [onPairedFrame] call,
 * the pairing result from that same call, the current viewport (from the most recent
 * [onViewportChanged] call), and the current session's intrinsics resolution (from the first
 * [onIntrinsicsResolved] call — see below). [onViewportChanged] and [onIntrinsicsResolved] each
 * rebuild against whatever frame/pairing pair is currently latest.
 *
 * **Intrinsics: resolved at most once per session.** [onIntrinsicsResolved] only accepts its
 * *first* call; later calls are no-ops. Combined with
 * `dev.pointtosky.mobile.ar.camera.SessionScopedCameraIntrinsicsResolver` (which itself only
 * invokes the real Camera2 characteristics lookup once), this guarantees the expensive lookup
 * never repeats per frame.
 *
 * **Thread safety.** One internal lock serializes every update method and [dispose] — a
 * generation/state-machine transition, not independent atomics — so a callback already in flight
 * on another thread when [dispose] runs either completes and publishes before [dispose] clears it,
 * or observes `disposed == true` once it acquires the lock after [dispose] (and does nothing);
 * there is no interleaving that republishes after disposal completes. `MobileLog` calls are always
 * issued from an immutable snapshot decided while holding the lock, never while holding it.
 *
 * **Disposal is terminal, not reusable**, matching CAM-1d's `CameraTimestampSynchronizer`
 * convention: [dispose] is idempotent, clears all cached inputs, publishes
 * [CameraSessionGeometryResult.Disposed], and every update method becomes a permanent no-op
 * afterward. One provider instance belongs to one AR camera session; a new session gets a new
 * `remember`ed instance rather than reusing a disposed one.
 */
class CameraSessionGeometryProvider {
    private val _state =
        MutableStateFlow<CameraSessionGeometryResult>(CameraSessionGeometryResult.MissingFrame(viewportSize = null))
    val state: StateFlow<CameraSessionGeometryResult> = _state.asStateFlow()

    // Single lock serializing every update method with the disposed transition - see the class
    // kdoc. Every field below is read/written only while holding it.
    private val lock = Any()
    private var disposed = false

    private var latestFrame: CameraFrameMetadata? = null
    private var latestPairingResult: FrameRotationPairingResult? = null
    private var viewportWidthPx = 0
    private var viewportHeightPx = 0
    private var intrinsicsResolution: CameraIntrinsicsResolution? = null

    private var observedFrameCount = 0L
    private var readyBundleCount = 0L
    private var rejectedBundleCount = 0L
    private var latestStatus = CameraSessionGeometryStatus.MISSING_FRAME
    private var latestQuality: CameraGeometryQuality? = null
    private var latestPairDeltaNanos: Long? = null

    private var recomputeCount = 0L
    private var loggedSessionStarted = false
    private var loggedFirstReady = false
    private var loggedFallbackIntrinsics = false
    private var lastLoggedStatus: CameraSessionGeometryStatus? = null

    /** Feeds one camera frame together with the pairing result computed for that same frame. No-op after [dispose]. */
    fun onPairedFrame(
        frame: CameraFrameMetadata,
        pairingResult: FrameRotationPairingResult,
    ) {
        val plan =
            synchronized(lock) {
                if (disposed) return
                observedFrameCount++
                latestFrame = frame
                latestPairingResult = pairingResult
                recomputeLocked()
            }
        runLog(plan)
    }

    /** Feeds the current AR overlay viewport size, in pixels. No-op after [dispose] or when unchanged. */
    fun onViewportChanged(
        widthPx: Int,
        heightPx: Int,
    ) {
        val plan =
            synchronized(lock) {
                if (disposed) return
                if (viewportWidthPx == widthPx && viewportHeightPx == heightPx) return
                viewportWidthPx = widthPx
                viewportHeightPx = heightPx
                recomputeLocked()
            }
        runLog(plan)
    }

    /** Feeds the session's resolved-or-fallback intrinsics. Only the first call per session has any effect. */
    fun onIntrinsicsResolved(resolution: CameraIntrinsicsResolution) {
        val plan =
            synchronized(lock) {
                if (disposed) return
                if (intrinsicsResolution != null) return
                intrinsicsResolution = resolution
                recomputeLocked()
            }
        runLog(plan)
    }

    /**
     * Terminal, idempotent: after this call every update method is a permanent no-op, [state]
     * becomes [CameraSessionGeometryResult.Disposed], and all cached inputs/counters are cleared.
     * Callers must invoke this exactly when the owning AR session ends.
     */
    fun dispose() {
        val plan =
            synchronized(lock) {
                if (disposed) return
                disposed = true
                latestFrame = null
                latestPairingResult = null
                intrinsicsResolution = null
                viewportWidthPx = 0
                viewportHeightPx = 0
                observedFrameCount = 0L
                readyBundleCount = 0L
                rejectedBundleCount = 0L
                latestStatus = CameraSessionGeometryStatus.DISPOSED
                latestQuality = null
                latestPairDeltaNanos = null
                _state.value = CameraSessionGeometryResult.Disposed
                LogPlan(disposed = true)
            }
        runLog(plan)
    }

    /** Debug snapshot for a minimal readout - no pixels, no matrices, only counters/status/quality. */
    fun debugState(): CameraSessionGeometryDebugState =
        synchronized(lock) {
            CameraSessionGeometryDebugState(
                observedFrameCount = observedFrameCount,
                readyBundleCount = readyBundleCount,
                rejectedBundleCount = rejectedBundleCount,
                latestStatus = latestStatus,
                latestQuality = latestQuality,
                latestPairDeltaNanos = latestPairDeltaNanos,
            )
        }

    /** Must be called while holding [lock]. Rebuilds [state] from whatever inputs are currently cached. */
    private fun recomputeLocked(): LogPlan {
        val frame = latestFrame
        val result: CameraSessionGeometryResult =
            when {
                frame == null -> CameraSessionGeometryResult.MissingFrame(currentViewportSizeOrNullLocked())

                viewportWidthPx <= 0 || viewportHeightPx <= 0 ->
                    CameraSessionGeometryResult.InvalidViewport(viewportWidthPx, viewportHeightPx)

                intrinsicsResolution == null ->
                    CameraSessionGeometryResult.IntrinsicsUnavailable(IntrinsicsUnavailableReason.PENDING)

                else -> {
                    val pairing =
                        requireNotNull(latestPairingResult) { "latestPairingResult must be set whenever latestFrame is set" }
                    val built =
                        createCameraSessionGeometry(
                            frame = frame,
                            pairingResult = pairing,
                            intrinsicsResolution = requireNotNull(intrinsicsResolution),
                            viewportWidthPx = viewportWidthPx,
                            viewportHeightPx = viewportHeightPx,
                        )
                    if (built !is CameraSessionGeometryResult.Ready) rejectedBundleCount++
                    built
                }
            }

        latestStatus = result.status
        if (result is CameraSessionGeometryResult.Ready) {
            readyBundleCount++
            latestQuality = result.quality
            latestPairDeltaNanos = result.geometry.frameRotationDeltaNanos
        }

        _state.value = result
        recomputeCount++
        return planLog(result)
    }

    private fun currentViewportSizeOrNullLocked(): PixelSize? =
        if (viewportWidthPx > 0 && viewportHeightPx > 0) {
            PixelSize(viewportWidthPx.toDouble(), viewportHeightPx.toDouble())
        } else {
            null
        }

    /** Must be called while holding [lock]. Computes what to log without calling [MobileLog] itself. */
    private fun planLog(result: CameraSessionGeometryResult): LogPlan {
        val sessionStarted = !loggedSessionStarted
        if (sessionStarted) loggedSessionStarted = true

        var firstReadyQuality: CameraGeometryQuality? = null
        var fallbackFirstUse = false
        if (result is CameraSessionGeometryResult.Ready) {
            if (!loggedFirstReady) {
                loggedFirstReady = true
                firstReadyQuality = result.quality
            }
            if (result.quality == CameraGeometryQuality.LEGACY_INTRINSICS_FALLBACK && !loggedFallbackIntrinsics) {
                loggedFallbackIntrinsics = true
                fallbackFirstUse = true
            }
        }

        val statusChanged = lastLoggedStatus != result.status
        lastLoggedStatus = result.status

        val summary =
            if (result is CameraSessionGeometryResult.Ready && recomputeCount % GEOMETRY_SUMMARY_LOG_INTERVAL == 0L) {
                SummaryLog(
                    status = result.status.name,
                    quality = result.quality.name,
                    bufferWidthPx = result.geometry.frame.bufferWidthPx,
                    bufferHeightPx = result.geometry.frame.bufferHeightPx,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                    rotationDegrees = result.geometry.frame.rotationDegrees,
                    pairDeltaMillis = result.geometry.frameRotationDeltaNanos / NANOS_PER_MILLI,
                    intrinsicsSource = result.geometry.intrinsics.intrinsics.source.name,
                )
            } else {
                null
            }

        return LogPlan(
            sessionStarted = sessionStarted,
            firstReadyQuality = firstReadyQuality,
            statusChanged = if (statusChanged) result.status else null,
            fallbackFirstUse = fallbackFirstUse,
            summary = summary,
        )
    }

    /** Must be called without holding [lock] - issues the actual (immutable, already-decided) log calls. */
    private fun runLog(plan: LogPlan) {
        if (plan.sessionStarted) MobileLog.cameraGeometrySessionStarted()
        plan.firstReadyQuality?.let { MobileLog.cameraGeometryFirstReady(quality = it.name) }
        plan.statusChanged?.let { MobileLog.cameraGeometryStatusChanged(status = it.name) }
        if (plan.fallbackFirstUse) MobileLog.cameraGeometryFallbackIntrinsicsInUse()
        plan.summary?.let { summary ->
            MobileLog.cameraGeometrySummary(
                status = summary.status,
                quality = summary.quality,
                bufferWidthPx = summary.bufferWidthPx,
                bufferHeightPx = summary.bufferHeightPx,
                viewportWidthPx = summary.viewportWidthPx,
                viewportHeightPx = summary.viewportHeightPx,
                rotationDegrees = summary.rotationDegrees,
                pairDeltaMillis = summary.pairDeltaMillis,
                intrinsicsSource = summary.intrinsicsSource,
            )
        }
        if (plan.disposed) MobileLog.cameraGeometryDisposed()
    }

    /** Immutable plan of which [MobileLog] events (if any) an update should fire, decided under [lock]. */
    private class LogPlan(
        val sessionStarted: Boolean = false,
        val firstReadyQuality: CameraGeometryQuality? = null,
        val statusChanged: CameraSessionGeometryStatus? = null,
        val fallbackFirstUse: Boolean = false,
        val summary: SummaryLog? = null,
        val disposed: Boolean = false,
    )

    private class SummaryLog(
        val status: String,
        val quality: String,
        val bufferWidthPx: Int,
        val bufferHeightPx: Int,
        val viewportWidthPx: Int,
        val viewportHeightPx: Int,
        val rotationDegrees: Int,
        val pairDeltaMillis: Long,
        val intrinsicsSource: String,
    )

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /** Throttle: log a geometry summary every 30th Ready recompute. */
        const val GEOMETRY_SUMMARY_LOG_INTERVAL = 30L
    }
}
