package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.CameraInfo
import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
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
 * **Thread safety.** One internal lock guards the state-machine transition ([cameraInfo] presence,
 * frame-dimension presence, [resolutionStarted], [disposed]) so [onCameraInfo] and
 * [onFrameMetadata] — called from different threads in production (the CameraX bind coroutine on
 * `Dispatchers.Main`, and the dedicated `ImageAnalysis` executor, respectively) — can never both
 * believe they claimed resolution. The actual [SessionScopedCameraIntrinsicsResolver.resolveOnce]
 * call (a real Camera2 `CameraCharacteristics` read) and the [onResolved] callback both run
 * **outside** the lock, so neither Camera2 work nor `CameraSessionGeometryProvider`/`MobileLog`
 * work ever happens while the lock is held.
 *
 * **Disposal.** [dispose] is terminal and idempotent. After it, [onCameraInfo]/[onFrameMetadata]
 * are permanent no-ops. A resolution already in flight when [dispose] runs is not cancelled — it
 * still completes the Camera2 read — but its result is discarded rather than published: disposal is
 * re-checked immediately before calling [onResolved], so a session ending while a resolution was
 * mid-flight can never publish intrinsics after disposal completes.
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
     * Feeds one analyzed camera frame; only its buffer dimensions matter here. No-op after
     * [dispose], or once a frame's dimensions have already been accepted — a later frame never
     * overrides the first.
     */
    fun onFrameMetadata(frame: CameraFrameMetadata) {
        val inputs =
            synchronized(lock) {
                if (disposed || frameWidthPx != null) return
                frameWidthPx = frame.bufferWidthPx
                frameHeightPx = frame.bufferHeightPx
                claimIfReadyLocked()
            }
        resolveAndPublish(inputs)
    }

    /** Terminal, idempotent: after this call, both update methods are permanent no-ops. */
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
        return ResolutionInputs(info, widthPx, heightPx)
    }

    /**
     * Must be called without holding [lock]. A `null` [inputs] means this call did not claim
     * resolution and there is nothing to do.
     */
    private fun resolveAndPublish(inputs: ResolutionInputs?) {
        if (inputs == null) return
        val resolution = resolver.resolveOnce(inputs.cameraInfo, inputs.widthPx, inputs.heightPx)
        val stillActive = synchronized(lock) { !disposed }
        if (stillActive) onResolved(resolution)
    }

    private class ResolutionInputs(
        val cameraInfo: CameraInfo,
        val widthPx: Int,
        val heightPx: Int,
    )
}
