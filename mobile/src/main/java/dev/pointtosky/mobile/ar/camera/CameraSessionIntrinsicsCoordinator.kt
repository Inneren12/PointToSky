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
 * Bounded, cheap, session-scoped snapshot of every [CameraSessionIntrinsicsCoordinator.onFrameMetadata]
 * call this coordinator has received (CAM-2c runtime integration fix §3) — unlike the coordinator's
 * own internal "first usable candidate" bookkeeping (which stops mattering once resolution is
 * claimed), these counters keep advancing for the lifetime of the session, so the diagnostics panel
 * can show real per-frame transform transport health even long after intrinsics resolved.
 *
 * @property framesAnalyzed every [CameraFrameMetadata] this coordinator has received, regardless of
 *   whether resolution had already started.
 * @property framesWithTransform the subset of [framesAnalyzed] whose [CameraFrameMetadata.sensorToBufferTransform]
 *   was non-`null`.
 * @property framesWithNullTransform the complementary subset — `sensorToBufferTransform == null`.
 * @property framesWithUsableTransform the subset of [framesWithTransform] that additionally
 *   classified as [SensorToBufferTransformClass.AXIS_ALIGNED_0] — the only class this codebase's
 *   calibrated mapping actually resolves (see [CameraSessionIntrinsicsCoordinator]'s own KDoc).
 *   **Naming note (CAM-2c domain-consistency fix):** despite the property name (kept as-is to limit
 *   this fix's scope — see this class's own KDoc for the full reasoning), this counts frames whose
 *   transform classified as a **structurally supported transform class**, never a claim that the
 *   transform's own numbers were checked for *semantic* source-to-buffer domain consistency — a real
 *   Pixel 9 session recorded every frame in this bucket while the reported matrix was, in fact, an
 *   identity matrix that cannot map its `4080x3072` active array onto its `640x480` analysis buffer
 *   (`docs/validation/cam_2c_pixel9_evidence.md`). Every user-facing/exported label derived from this
 *   counter (`CamDiagnosticReportFormat`/`CamDiagnosticSnapshotJson`) says "supported transform class,"
 *   never "usable"; see `dev.pointtosky.core.astro.projection.camera.assessSensorToBufferDomainConsistency`
 *   for the separate, semantic check.
 * @property coordinatorFramesWaited how many frames the coordinator's own coherent-input gate
 *   actually counted against [CameraSessionIntrinsicsCoordinator]'s `maxFramesWaitingForUsableTransform`
 *   bound before resolution was claimed (frozen once resolution starts) — distinct from
 *   [framesAnalyzed], which never stops counting.
 * @property latestFrameTransform the most recently received frame's raw [SensorToBufferMatrix3], or
 *   `null` if that frame carried none — always the *latest* frame, even after resolution.
 * @property latestFrameTransformClass [latestFrameTransform]'s own [SensorToBufferTransformClass],
 *   or `null` when [latestFrameTransform] itself is `null`.
 */
data class CameraSessionIntrinsicsFrameCounters(
    val framesAnalyzed: Long,
    val framesWithTransform: Long,
    val framesWithNullTransform: Long,
    val framesWithUsableTransform: Long,
    val coordinatorFramesWaited: Int,
    val latestFrameTransform: SensorToBufferMatrix3?,
    val latestFrameTransformClass: SensorToBufferTransformClass?,
)

/**
 * (CAM-2c runtime integration fix §2) A session's full CAM-2c picture in one bounded, immutable
 * snapshot — never collapsing the calibrated-mapping *attempt* into whichever result was ultimately
 * published. See [SessionScopedCameraIntrinsicsResolver.lastAnalysisBufferAttempt]'s KDoc for why the
 * two must stay distinguishable: a real Pixel 9 session can legitimately publish a `PhysicalSensor`
 * CAM-1b fallback while [analysisBufferAttempt] still carries the exact reason
 * (`UnsupportedLogicalMultiCameraMapping`, `MissingSensorToBufferTransform`, ...) the calibrated
 * mapping itself was rejected for.
 *
 * @property analysisBufferAttempt the exact CAM-2c outcome this session's resolution attempt
 *   produced — `null` only when no calibrated attempt was ever made (no analyzed frame known yet).
 * @property publishedIntrinsicsResolution the final resolution this session actually published to
 *   `CameraSessionGeometryProvider` — `null` before resolution has completed.
 * @property coordinatorState this coordinator's own [CameraSessionIntrinsicsCoordinatorState] as of
 *   this snapshot.
 * @property cameraCharacteristicsSnapshot the raw Camera2 metadata this attempt actually read,
 *   regardless of outcome.
 * @property frameCounters this coordinator's running per-frame transform-transport counters.
 */
data class CameraSessionIntrinsicsDiagnosticState(
    val analysisBufferAttempt: AnalysisBufferIntrinsicsResolution?,
    val publishedIntrinsicsResolution: CoreCameraIntrinsicsResolution?,
    val coordinatorState: CameraSessionIntrinsicsCoordinatorState,
    val cameraCharacteristicsSnapshot: CameraCharacteristicsSnapshot?,
    val frameCounters: CameraSessionIntrinsicsFrameCounters,
)

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

    // CAM-2c runtime integration fix §3 - running per-frame transport counters. Unlike framesSeen/
    // usableTransform above (which freeze once resolution starts, since they only exist to drive the
    // coherent-input gate), these keep advancing for the coordinator's whole lifetime.
    private var framesAnalyzed = 0L
    private var framesWithTransform = 0L
    private var framesWithNullTransform = 0L
    private var framesWithUsableTransform = 0L
    private var latestFrameTransform: SensorToBufferMatrix3? = null
    private var latestFrameTransformClass: SensorToBufferTransformClass? = null

    /** (CAM-2c fix §4) This coordinator's current observable state — see [CameraSessionIntrinsicsCoordinatorState]. */
    val state: CameraSessionIntrinsicsCoordinatorState
        get() = synchronized(lock) { stateLocked() }

    /** (CAM-2c runtime integration fix §3) This coordinator's running per-frame transport counters. */
    val frameCounters: CameraSessionIntrinsicsFrameCounters
        get() =
            synchronized(lock) {
                CameraSessionIntrinsicsFrameCounters(
                    framesAnalyzed = framesAnalyzed,
                    framesWithTransform = framesWithTransform,
                    framesWithNullTransform = framesWithNullTransform,
                    framesWithUsableTransform = framesWithUsableTransform,
                    coordinatorFramesWaited = framesSeen,
                    latestFrameTransform = latestFrameTransform,
                    latestFrameTransformClass = latestFrameTransformClass,
                )
            }

    /**
     * (CAM-2c runtime integration fix §2) This session's full CAM-2c picture — the resolver's typed
     * attempt and the value it actually published, together with this coordinator's own state and
     * frame counters. See [CameraSessionIntrinsicsDiagnosticState]'s KDoc.
     *
     * **Observational, not one atomic snapshot.** [resolver]'s `lastAnalysisBufferAttempt`/
     * `lastPublishedResolution`/`lastCameraCharacteristicsSnapshot` and this coordinator's own
     * [state]/[frameCounters] are each read through their own `synchronized` getter, one after
     * another — this property itself holds no lock across all of them. This is debug-only display
     * (never consumed by projection or resolution) so that is an intentional, accepted tradeoff, not
     * a broadening into a reactive/transactional read: [resolver]'s three fields are themselves
     * guaranteed internally consistent with each other (all three are written together, under
     * [resolver]'s own lock, by the one `resolveOnce` call that ever sets them — see
     * [SessionScopedCameraIntrinsicsResolver.resolveOnce] and the single-snapshot-per-resolution
     * guarantee in [resolveCameraIntrinsicsPreferringCalibration]'s KDoc), so a caller reading this
     * property mid-resolution sees either the fully-pre-resolution values (all three still `null`) or
     * the fully-post-resolution values (all three set from the one shared
     * [CameraCharacteristicsSnapshot]) — never a mix of one resolution's attempt with another
     * resolution's snapshot, since [resolveOnce] only ever runs once per session. Only [frameCounters]
     * can legitimately be read from a moment adjacent to (fractionally before or after) the other four
     * fields, since it keeps advancing independently for the coordinator's whole lifetime — a caller
     * must not assume [CameraSessionIntrinsicsFrameCounters] was captured at exactly the same instant
     * as [CameraSessionIntrinsicsDiagnosticState.analysisBufferAttempt] etc., only that both describe
     * the same session.
     */
    val diagnosticState: CameraSessionIntrinsicsDiagnosticState
        get() =
            CameraSessionIntrinsicsDiagnosticState(
                analysisBufferAttempt = resolver.lastAnalysisBufferAttempt,
                publishedIntrinsicsResolution = resolver.lastPublishedResolution,
                coordinatorState = state,
                cameraCharacteristicsSnapshot = resolver.lastCameraCharacteristicsSnapshot,
                frameCounters = frameCounters,
            )

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
                if (disposed) return
                // CAM-2c runtime integration fix §3: these counters keep advancing for the whole
                // session, even once resolutionStarted below would otherwise make this call a no-op -
                // a diagnostics panel needs to see live per-frame transport health long after
                // intrinsics resolved, not just during the coherent-input gate's own bounded wait.
                framesAnalyzed += 1
                val transform = frame.sensorToBufferTransform
                latestFrameTransform = transform
                latestFrameTransformClass = transform?.let { classifySensorToBufferMatrix(it) }
                if (transform == null) {
                    framesWithNullTransform += 1
                } else {
                    framesWithTransform += 1
                    if (isSupportedTransformClass(transform)) framesWithUsableTransform += 1
                }

                if (resolutionStarted) return
                framesSeen += 1
                lastFrameWidthPx = frame.bufferWidthPx
                lastFrameHeightPx = frame.bufferHeightPx
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
