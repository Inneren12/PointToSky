package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.CameraInfo
import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransform
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution as CoreCameraIntrinsicsResolution

/**
 * Coordinates CAM-1f intrinsics resolution so it only ever runs once **real** inputs are known on
 * both sides: the bound [CameraInfo] (from `CameraPreview.onCameraInfo`) and the first analyzed
 * [CameraFrameMetadata] buffer dimensions (from `CameraPreview.onFrameMetadata`).
 *
 * Resolving as soon as [CameraInfo] alone is available — the naive approach — is wrong for the
 * fallback path: `legacyFallbackCameraIntrinsics` derives its horizontal FOV from the analyzed
 * image's aspect ratio, and at the moment a CameraX bind completes, the first `ImageAnalysis` frame
 * has usually not arrived yet. Resolving then would cache a wrong (default/square) fallback
 * horizontal FOV for the entire session, since resolution only ever happens once.
 * [SessionScopedCameraIntrinsicsResolver.resolveOnce] now requires real, positive dimensions; this
 * coordinator is what guarantees they are actually available before it is ever called.
 *
 * Accepts either callback order — [onCameraInfo] before [onFrameMetadata] or vice versa — CameraX
 * gives no guarantee which arrives first. Resolution is claimed **atomically**, exactly once, by
 * whichever call supplies the second missing input; a repeated call to either method, after its own
 * input has already been accepted, is a no-op — the *first* accepted `CameraInfo`/frame dimensions
 * are what get used, never a later one. If only [onCameraInfo] ever arrives — the CameraX
 * Preview-only fallback bind (§4.4), where no `ImageAnalysis` frame is ever produced — resolution
 * never happens: CAM-1f geometry correctly stays unavailable in that case (no frame means no
 * pairing means no bundle either), and Preview-only camera display is otherwise unaffected. No
 * `PreviewView` dimension is ever substituted for the missing analyzed-frame dimensions.
 *
 * **Thread safety.** One internal [lock] guards the state-machine transition ([cameraInfo]
 * presence, frame-dimension presence, [resolutionStarted], [disposed]) so [onCameraInfo] and
 * [onFrameMetadata] — called from different threads in production (the CameraX bind coroutine on
 * `Dispatchers.Main`, and the dedicated `ImageAnalysis` executor, respectively) — can never both
 * believe they claimed resolution. The real Camera2 work,
 * [SessionScopedCameraIntrinsicsResolver.resolveOnce], runs **outside** [lock] — it must never
 * block [onCameraInfo]/[onFrameMetadata] callers on the CameraX main-thread coroutine or the
 * analysis executor. The final [onResolved] publication, however, **is serialized with [dispose]
 * under [lock]** (see below) — a deliberate, reviewed exception to "no work while holding the
 * lock", because [onResolved] is documented to be a small, non-blocking publish
 * (`CameraSessionGeometryProvider.onIntrinsicsResolved` in production), not further Camera2/IO
 * work.
 *
 * **Disposal — exact linearization rule.** [dispose] is terminal and idempotent. After it
 * *returns*, [onCameraInfo]/[onFrameMetadata] are permanent no-ops, **and no [onResolved] call can
 * begin afterward**:
 * ```text
 * Final onResolved publication and dispose() are serialized on the same lock.
 * A publication already holding the lock (i.e. already invoking onResolved) finishes
 * before a concurrent dispose() can acquire the lock and return.
 * No onResolved call can begin after dispose() has returned.
 * ```
 * This closes a real race an earlier revision had: re-checking `disposed` *before* calling
 * [onResolved] (a "read `disposed`, release the lock, then call `onResolved`" sequence) left a
 * window where `dispose()` could complete *between* that check and the call, so a callback could
 * begin after `dispose()` had already returned — violating this class's own terminal-lifecycle
 * contract. Publication and disposal now share [lock] for the whole "check disposed, call
 * `onResolved`" step, so there is no window between the check and the call for `dispose()` to run
 * in.
 *
 * A resolution already in flight *inside `resolveOnce`* when [dispose] runs is not cancelled — it
 * still completes the Camera2 read, outside the lock, since that must never be blocked on
 * disposal — but once it returns and reaches the now-lock-guarded publish step, if `dispose()` has
 * already run (or wins the race for [lock]), the result is discarded rather than published.
 *
 * **Lock-order note.** [onResolved] is called while holding [lock]. This is safe only because the
 * production callback (`geometryProvider::onIntrinsicsResolved`) takes its own, entirely separate
 * lock internally and never calls back into this coordinator — so there is no cycle and no reverse
 * lock acquisition. Any future [onResolved] implementation must preserve that: it must not call
 * back into this coordinator (directly or transitively) while it runs, or a deadlock becomes
 * possible.
 *
 * One instance belongs to one bound camera session, alongside one
 * [SessionScopedCameraIntrinsicsResolver] and one `CameraSessionGeometryProvider`; a new AR session
 * gets new instances of all three.
 */
class CameraSessionIntrinsicsCoordinator(
    private val resolver: SessionScopedCameraIntrinsicsResolver,
    private val onResolved: (CoreCameraIntrinsicsResolution) -> Unit,
) {
    private val lock = Any()
    private var cameraInfo: CameraInfo? = null
    private var frameWidthPx: Int? = null
    private var frameHeightPx: Int? = null
    private var sensorToBufferTransform: SensorToBufferTransform? = null
    private var resolutionStarted = false
    private var disposed = false

    /** Feeds the real, bound [CameraInfo]. No-op after [dispose], or if [CameraInfo] was already supplied. */
    fun onCameraInfo(cameraInfo: CameraInfo) {
        val inputs =
            synchronized(lock) {
                if (disposed || this.cameraInfo != null) return
                this.cameraInfo = cameraInfo
                claimIfReadyLocked()
            }
        resolveAndPublish(inputs)
    }

    /**
     * Feeds one analyzed camera frame; its buffer dimensions and (CAM-2c) real per-frame
     * `frame.sensorToBufferTransform` matter here — captured together, atomically, from this exact
     * frame, never mixed with a different frame's transform. No-op after [dispose], or once a
     * frame's dimensions have already been accepted — a later frame never overrides the first
     * (including its `sensorToBufferTransform`, even if that first frame's was `null` and a later
     * one's would not be).
     */
    fun onFrameMetadata(frame: CameraFrameMetadata) {
        val inputs =
            synchronized(lock) {
                if (disposed || frameWidthPx != null) return
                frameWidthPx = frame.bufferWidthPx
                frameHeightPx = frame.bufferHeightPx
                sensorToBufferTransform = frame.sensorToBufferTransform
                claimIfReadyLocked()
            }
        resolveAndPublish(inputs)
    }

    /**
     * Terminal, idempotent: after this call *returns*, both update methods are permanent no-ops,
     * and no [onResolved] call can begin afterward. If a publication is already in progress
     * (already holding [lock] inside [resolveAndPublish]), this call blocks until that publication
     * finishes before it can acquire [lock] and return — see the class kdoc's exact linearization
     * rule.
     */
    fun dispose() {
        synchronized(lock) {
            disposed = true
        }
    }

    /**
     * Must be called while holding [lock]. Returns the captured resolution inputs if this call is
     * the one that just satisfied both sides, or `null` if resolution was already claimed, or a
     * required input is still missing.
     */
    private fun claimIfReadyLocked(): ResolutionInputs? {
        if (resolutionStarted) return null
        val info = cameraInfo ?: return null
        val widthPx = frameWidthPx ?: return null
        val heightPx = frameHeightPx ?: return null
        resolutionStarted = true
        return ResolutionInputs(info, widthPx, heightPx, sensorToBufferTransform)
    }

    /**
     * A `null` [inputs] means this call did not claim resolution and there is nothing to do. The
     * [resolver.resolveOnce] call itself runs without holding [lock] (real Camera2 work must never
     * block a concurrent [onCameraInfo]/[onFrameMetadata]/[dispose] caller), but the final
     * disposed-check-and-publish step below re-acquires [lock] and holds it for the whole step —
     * see the class kdoc's "exact linearization rule". This is what removes the check-then-act
     * window a bare `if (!disposed) onResolved(...)` (without the lock held across both) would
     * still have.
     */
    private fun resolveAndPublish(inputs: ResolutionInputs?) {
        if (inputs == null) return
        val resolution = resolver.resolveOnce(inputs.cameraInfo, inputs.widthPx, inputs.heightPx, inputs.sensorToBufferTransform)
        synchronized(lock) {
            if (disposed) return
            onResolved(resolution)
        }
    }

    private class ResolutionInputs(
        val cameraInfo: CameraInfo,
        val widthPx: Int,
        val heightPx: Int,
        val sensorToBufferTransform: SensorToBufferTransform?,
    )
}
