package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.CameraInfo
import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import dev.pointtosky.core.astro.projection.camera.classifySensorToBufferMatrix
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution as CoreCameraIntrinsicsResolution

/**
 * Observable lifecycle state of a [CameraSessionIntrinsicsCoordinator] (CAM-2c fix §4) — a derived
 * snapshot of its internal fields, for tests and diagnostics; never itself a source of truth (the
 * coordinator's own synchronized fields are).
 */
enum class CameraSessionIntrinsicsCoordinatorState {
    /** Neither [CameraSessionIntrinsicsCoordinator.onCameraInfo] nor a frame has arrived yet. */
    WAITING_FOR_CAMERA_INFO,

    /** [CameraSessionIntrinsicsCoordinator.onCameraInfo] arrived; no frame has arrived yet. */
    WAITING_FOR_FRAME,

    /**
     * At least one frame has arrived, but none yet supplied a *usable* sensor-to-buffer transform
     * (non-`null` and [SensorToBufferTransformClass.AXIS_ALIGNED_0] — CAM-2c fix round 2 §4) — see
     * [CameraSessionIntrinsicsCoordinator]'s KDoc on the coherent-input gate and its bounded fallback.
     */
    WAITING_FOR_USABLE_SENSOR_TO_BUFFER_TRANSFORM,

    /** Both inputs are ready and [dev.pointtosky.mobile.ar.camera.SessionScopedCameraIntrinsicsResolver.resolveOnce] is running. */
    RESOLVING,

    /** Resolution completed and was published via `onResolved`. Terminal (barring [DISPOSED]). */
    RESOLVED,

    /** [CameraSessionIntrinsicsCoordinator.dispose] has been called. Terminal. */
    DISPOSED,
}

/**
 * Coordinates CAM-1f intrinsics resolution so it only ever runs once **real, coherent** inputs are
 * known: the bound [CameraInfo] (from `CameraPreview.onCameraInfo`) and one analyzed
 * [CameraFrameMetadata] frame (from `CameraPreview.onFrameMetadata`) whose own buffer dimensions
 * *and* sensor-to-buffer transform are used together — never a later frame's transform paired with an
 * earlier frame's dimensions, or vice versa (CAM-2c fix §4).
 *
 * ## Why "the first frame" alone is not enough (the defect this fix closes)
 * An earlier revision of this class accepted the *first* frame's dimensions and
 * `sensorToBufferTransform` unconditionally, then latched — permanently ignoring every later frame,
 * even if that first frame's transform was `null` (not yet populated by CameraX) or geometrically
 * unusable (see [SensorToBufferTransformClass]) while a *later* frame's would have been fine. That
 * meant a single early hiccup (or, under the pre-fix axis-aligned-only assumption, a merely rotated
 * matrix) could permanently deny the calibrated `AnalysisBuffer` mapping for an entire session, even
 * though a perfectly usable transform arrived moments later.
 *
 * ## The coherent-input gate
 * [onFrameMetadata] now keeps accepting frames (while resolution has not yet started) and classifies
 * each one's own `frame.sensorToBufferTransform` via [classifySensorToBufferMatrix]. The **first**
 * frame whose transform is non-`null` and classifies as [SensorToBufferTransformClass.AXIS_ALIGNED_0]
 * — the only class `dev.pointtosky.mobile.ar.camera.resolveAnalysisBufferIntrinsics` will actually
 * resolve into a calibrated result as of CAM-2c fix round 2 §4 (`ORTHOGONAL_90`/`180`/`270` are
 * algebraically composable but not proven correct in combination with `rotationDegrees`, so the
 * resolver rejects them too) — has its dimensions *and* transform captured together, atomically, as
 * the one pair ever used — never mixed with any other frame's data.
 *
 * ## Bounded wait, not an unbounded queue
 * No frame is ever queued or buffered — only the running "first usable" candidate (if any) and the
 * most recent frame's dimensions (for the fallback below) are retained; older, rejected frames are
 * discarded immediately, in O(1) space regardless of session length. If [maxFramesWaitingForUsableTransform]
 * frames have been seen with no usable transform found yet, this coordinator gives up waiting and
 * resolves using the most recent frame's dimensions with a `null` transform — [resolveOnce] then
 * skips the calibrated `AnalysisBuffer` path in favor of the existing, already-typed CAM-1b path
 * (`PhysicalSensor`-referenced metadata or the explicit legacy fallback), never a fabricated
 * calibrated result. This bound exists so a device that genuinely never reports a usable transform
 * (e.g. every frame is [SensorToBufferTransformClass.MIRRORED]/`GENERAL_AFFINE_UNSUPPORTED`/etc.)
 * still reaches a resolved state promptly rather than waiting forever.
 *
 * Accepts either callback order — [onCameraInfo] before [onFrameMetadata] or vice versa — CameraX
 * gives no guarantee which arrives first. Resolution is claimed **atomically**, exactly once, by
 * whichever call first satisfies both "camera info known" and "a usable frame pair known (or the
 * bound reached)". A repeated call to either method, after resolution has already been claimed, is a
 * no-op.
 *
 * **Thread safety.** One internal [lock] guards the state machine (state described by [state]) so
 * [onCameraInfo] and [onFrameMetadata] — called from different threads in production (the CameraX
 * bind coroutine on `Dispatchers.Main`, and the dedicated `ImageAnalysis` executor, respectively) —
 * can never both believe they claimed resolution. The real Camera2 work,
 * [SessionScopedCameraIntrinsicsResolver.resolveOnce], runs **outside** [lock] — it must never block
 * [onCameraInfo]/[onFrameMetadata] callers on the CameraX main-thread coroutine or the analysis
 * executor. The final [onResolved] publication, however, **is serialized with [dispose] under
 * [lock]** (see below) — a deliberate, reviewed exception to "no work while holding the lock",
 * because [onResolved] is documented to be a small, non-blocking publish
 * (`CameraSessionGeometryProvider.onIntrinsicsResolved` in production), not further Camera2/IO work.
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
    private val maxFramesWaitingForUsableTransform: Int = MAX_FRAMES_WAITING_FOR_USABLE_TRANSFORM,
    private val onResolved: (CoreCameraIntrinsicsResolution) -> Unit,
) {
    private val lock = Any()
    private var cameraInfo: CameraInfo? = null
    private var framesSeen = 0
    private var lastFrameWidthPx: Int? = null
    private var lastFrameHeightPx: Int? = null
    private var usableWidthPx: Int? = null
    private var usableHeightPx: Int? = null
    private var usableTransform: SensorToBufferMatrix3? = null
    private var resolutionStarted = false
    private var resolved = false
    private var disposed = false

    /** (CAM-2c fix §4) This coordinator's current observable state — see [CameraSessionIntrinsicsCoordinatorState]. */
    val state: CameraSessionIntrinsicsCoordinatorState
        get() = synchronized(lock) { stateLocked() }

    private fun stateLocked(): CameraSessionIntrinsicsCoordinatorState =
        when {
            disposed -> CameraSessionIntrinsicsCoordinatorState.DISPOSED
            resolved -> CameraSessionIntrinsicsCoordinatorState.RESOLVED
            resolutionStarted -> CameraSessionIntrinsicsCoordinatorState.RESOLVING
            cameraInfo == null -> CameraSessionIntrinsicsCoordinatorState.WAITING_FOR_CAMERA_INFO
            framesSeen == 0 -> CameraSessionIntrinsicsCoordinatorState.WAITING_FOR_FRAME
            else -> CameraSessionIntrinsicsCoordinatorState.WAITING_FOR_USABLE_SENSOR_TO_BUFFER_TRANSFORM
        }

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
     * Feeds one analyzed camera frame. Unlike [onCameraInfo], this is **not** a no-op after the first
     * call — it keeps accepting frames (classifying each one's own transform) until resolution is
     * actually claimed (a usable transform found, or [maxFramesWaitingForUsableTransform] reached).
     * See the class KDoc's "coherent-input gate" section for the full rule. No-op after [dispose], or
     * once resolution has already been claimed by some earlier call.
     */
    fun onFrameMetadata(frame: CameraFrameMetadata) {
        val inputs =
            synchronized(lock) {
                if (disposed || resolutionStarted) return
                framesSeen += 1
                lastFrameWidthPx = frame.bufferWidthPx
                lastFrameHeightPx = frame.bufferHeightPx
                val transform = frame.sensorToBufferTransform
                if (usableTransform == null && transform != null && isSupportedTransformClass(transform)) {
                    usableWidthPx = frame.bufferWidthPx
                    usableHeightPx = frame.bufferHeightPx
                    usableTransform = transform
                }
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
     * the one that just satisfied readiness (a usable transform found, or the bounded-wait fallback
     * triggered), or `null` if resolution was already claimed or a required input is still missing.
     */
    private fun claimIfReadyLocked(): ResolutionInputs? {
        if (resolutionStarted) return null
        val info = cameraInfo ?: return null

        val transform = usableTransform
        if (transform != null) {
            resolutionStarted = true
            return ResolutionInputs(info, checkNotNull(usableWidthPx), checkNotNull(usableHeightPx), transform)
        }

        if (framesSeen >= maxFramesWaitingForUsableTransform) {
            val widthPx = lastFrameWidthPx ?: return null
            val heightPx = lastFrameHeightPx ?: return null
            resolutionStarted = true
            return ResolutionInputs(info, widthPx, heightPx, null)
        }
        return null
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
            resolved = true
            onResolved(resolution)
        }
    }

    private class ResolutionInputs(
        val cameraInfo: CameraInfo,
        val widthPx: Int,
        val heightPx: Int,
        val sensorToBufferTransform: SensorToBufferMatrix3?,
    )

    private companion object {
        /**
         * ~1 second of frames at a typical 30fps `ImageAnalysis` rate (CAM-2c fix §4) — long enough
         * that an early transient hiccup (transform not yet populated, or one early unsupported-class
         * frame) does not matter, short enough that a device whose camera genuinely never reports a
         * usable transform still reaches a resolved (fallback) state promptly rather than leaving the
         * AR overlay without any intrinsics for many seconds.
         */
        const val MAX_FRAMES_WAITING_FOR_USABLE_TRANSFORM = 30

        /**
         * (CAM-2c fix round 2 §4) Only [SensorToBufferTransformClass.AXIS_ALIGNED_0] is usable — the
         * only class `dev.pointtosky.mobile.ar.camera.resolveAnalysisBufferIntrinsics` will actually
         * resolve into a calibrated result. `ORTHOGONAL_90`/`180`/`270` are algebraically composable
         * (see `dev.pointtosky.core.astro.projection.camera.mapActiveArrayIntrinsicsThroughMatrix`) but
         * not proven correct in combination with `CameraFrameMetadata.rotationDegrees`, so the resolver
         * rejects them as `RotationOwnershipUnproven` — this coordinator must not "claim" one of those
         * frames as its usable candidate and then stop looking, since the resolver would immediately
         * reject it anyway, wasting the one candidate slot this bounded gate keeps.
         */
        fun isSupportedTransformClass(matrix: SensorToBufferMatrix3): Boolean =
            classifySensorToBufferMatrix(matrix) == SensorToBufferTransformClass.AXIS_ALIGNED_0
    }
}
