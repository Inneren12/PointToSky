package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.CameraInfo
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution as CoreCameraIntrinsicsResolution

/**
 * Resolves CAM-1b camera intrinsics for one bound camera session **at most once**, then reuses the
 * result (CAM-1f). Wraps [CameraIntrinsicsProvider] — which itself performs the real Camera2
 * `CameraCharacteristics` read on every call — so that repeated per-frame callers (e.g. once
 * `CameraInfo` becomes available after a CameraX bind) never repeat the underlying lookup.
 *
 * Maps the CAM-1b `CameraIntrinsicsResolution(intrinsics, fallbackReason: String?)` shape into the
 * CAM-1f sealed `dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution`
 * (`Resolved`/`LegacyFallback`) exactly once, at the point of resolution — callers of this class
 * never see the mobile-side shape.
 *
 * One instance belongs to one bound camera session (`remember`ed alongside
 * [CameraSessionGeometryProvider] in `ArScreen`); a new AR session gets a new instance, so
 * re-entering AR always attempts a fresh resolution rather than reusing a stale one.
 *
 * [resolveOnce] itself performs no exception handling: [CameraIntrinsicsProvider.resolve] already
 * catches ordinary failures internally and reports them as [CoreCameraIntrinsicsResolution.LegacyFallback],
 * and any `CancellationException` it lets through propagates unchanged here.
 */
class SessionScopedCameraIntrinsicsResolver(
    private val provider: CameraIntrinsicsProvider = Camera2CameraIntrinsicsProvider(),
) {
    private val lock = Any()
    private var resolved: CoreCameraIntrinsicsResolution? = null
    private var analysisBufferAttempt: AnalysisBufferIntrinsicsResolution? = null
    private var cameraCharacteristicsSnapshot: CameraCharacteristicsSnapshot? = null

    /**
     * (CAM-2c runtime integration fix) This session's exact, typed CAM-2c attempt — `Resolved` when
     * the calibrated `AnalysisBuffer` mapping succeeded, one of its explicit failure variants when it
     * did not, or `null` before [resolveOnce] has run. Unlike [lastCalibrationDiagnostics], this is
     * populated for **every** outcome, not only a successful one — see
     * [resolveCameraIntrinsicsPreferringCalibration]'s KDoc. Debug-only; never read by projection.
     */
    val lastAnalysisBufferAttempt: AnalysisBufferIntrinsicsResolution?
        get() = synchronized(lock) { analysisBufferAttempt }

    /**
     * (CAM-2c §9) The [CameraCalibrationDiagnostics] this session's resolution produced, when the
     * calibrated `AnalysisBuffer` mapping actually succeeded — `null` before [resolveOnce] has run,
     * or whenever this session resolved a `PhysicalSensor`/legacy-fallback result instead. Debug-only;
     * never read by projection.
     */
    val lastCalibrationDiagnostics: CameraCalibrationDiagnostics?
        get() = synchronized(lock) { (analysisBufferAttempt as? AnalysisBufferIntrinsicsResolution.Resolved)?.diagnostics }

    /**
     * (CAM-2c runtime integration fix) The raw [CameraCharacteristicsSnapshot] this session's
     * resolution attempt actually read from the bound camera — captured regardless of
     * [lastAnalysisBufferAttempt]'s outcome. `null` before [resolveOnce] has run, or if
     * characteristics could not be read at all. Debug-only; never read by projection.
     */
    val lastCameraCharacteristicsSnapshot: CameraCharacteristicsSnapshot?
        get() = synchronized(lock) { cameraCharacteristicsSnapshot }

    /**
     * (CAM-2c runtime integration fix) The exact [CoreCameraIntrinsicsResolution] this session
     * published (the same value returned by [resolveOnce] and handed to `onResolved`) — `null`
     * before [resolveOnce] has run. Debug-only convenience so a diagnostics consumer does not need to
     * separately cache [resolveOnce]'s return value itself.
     */
    val lastPublishedResolution: CoreCameraIntrinsicsResolution?
        get() = synchronized(lock) { resolved }

    /**
     * Returns the cached resolution if this session already resolved one; otherwise resolves via
     * [provider], caches it, and returns it.
     *
     * [imageWidthPx]/[imageHeightPx] must be the **real, analyzed** `ImageAnalysis` buffer
     * dimensions — required and validated positive, not optional. The real per-device
     * `CAMERA_CHARACTERISTICS` path does not depend on them, but the legacy fallback path
     * (`dev.pointtosky.core.astro.projection.camera.legacyFallbackCameraIntrinsics`) derives its
     * horizontal FOV from their aspect ratio; calling this before the first analyzed frame's real
     * dimensions are known — e.g. with a guessed or default size — would cache a wrong fallback
     * horizontal FOV for the rest of the session, since resolution only ever happens once. Callers
     * must wait for real dimensions themselves; see `CameraSessionIntrinsicsCoordinator`, whose
     * entire purpose is guaranteeing that before calling this method in production.
     *
     * @param sensorToBufferTransform (CAM-2c) the real per-frame sensor-to-buffer mapping for the
     *   exact frame [imageWidthPx]/[imageHeightPx] describe, when known. `null` skips the calibrated
     *   `AnalysisBuffer` path in favor of the CAM-1b path — see [CameraIntrinsicsProvider.resolve].
     * @throws IllegalArgumentException if [imageWidthPx] or [imageHeightPx] is not strictly
     *   positive — a programmer-contract violation at the call site, not an expected runtime
     *   outcome (a real `CameraFrameMetadata` can never carry a non-positive buffer dimension).
     */
    fun resolveOnce(
        cameraInfo: CameraInfo,
        imageWidthPx: Int,
        imageHeightPx: Int,
        sensorToBufferTransform: SensorToBufferMatrix3? = null,
    ): CoreCameraIntrinsicsResolution {
        require(imageWidthPx > 0) { "imageWidthPx must be strictly positive; was $imageWidthPx" }
        require(imageHeightPx > 0) { "imageHeightPx must be strictly positive; was $imageHeightPx" }
        return synchronized(lock) {
            resolved ?: run {
                val result = provider.resolve(cameraInfo, imageWidthPx, imageHeightPx, sensorToBufferTransform)
                analysisBufferAttempt = result.analysisBufferAttempt
                cameraCharacteristicsSnapshot = result.cameraCharacteristicsSnapshot
                result.toCore().also { resolved = it }
            }
        }
    }

    private fun CameraIntrinsicsResolution.toCore(): CoreCameraIntrinsicsResolution =
        fallbackReason?.let { reason -> CoreCameraIntrinsicsResolution.LegacyFallback(intrinsics, reason) }
            ?: CoreCameraIntrinsicsResolution.Resolved(intrinsics)
}
