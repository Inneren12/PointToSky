package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState

/**
 * Bounded, `internalDebug`-only, active-array-local crop rectangle (pixels) captured verbatim from a
 * [CameraGeometryDiagnosticSnapshot] at [captureCamDiagnosticSnapshot] time - a plain value, never a
 * reference back into any live/mutable provider.
 */
data class CamDiagnosticCropRect(
    val leftPx: Int,
    val topPx: Int,
    val rightPx: Int,
    val bottomPx: Int,
)

/**
 * A bounded, fully immutable, `internalDebug`-only snapshot of one CAM diagnostics moment (diagnostic
 * export/freeze fix §1) - every field is a *value*, copied out of whatever live provider/coordinator
 * produced it at [capturedAtEpochMillis], never a reference a background thread could keep mutating
 * underneath a caller holding this snapshot. This is the one type the deterministic text
 * ([buildCamDiagnosticReportText]), the compact HUD summary ([buildCamDiagnosticCompactSummaryText]),
 * and the versioned JSON export ([buildCamDiagnosticJson]) all read from - never a second, independent
 * read of the live coordinators/providers, so "Freeze snapshot" and "Copy all"/"Share" are guaranteed to
 * describe the exact same moment.
 *
 * Carries no star catalog contents and no image pixels - only the plain scalars/enums/strings
 * [CameraGeometryDiagnosticSnapshot], [CameraSessionIntrinsicsDiagnosticState],
 * [CameraCalibrationDiagnostics], and [PredictedStarOverlayState] already carry for their own,
 * pre-existing debug panels (each of those types documents the same "no matrix/pixel/catalog data"
 * bound on itself).
 *
 * @property capturedAtEpochMillis wall-clock time this snapshot was captured, milliseconds since the
 *   Unix epoch (`System.currentTimeMillis()` at the call site - never computed inside this pure type).
 * @property sessionId the CAM-1g/CAM-2b/CAM-2c debug session identifier this snapshot describes (see
 *   [nextDebugSessionId]) - local, non-persistent, never a device identifier.
 * @property cam2bState CAM-2b's own [PredictedStarOverlayState] as of [capturedAtEpochMillis] - already
 *   an immutable value type (see that type's own KDoc), so it is carried here without transformation.
 * @property cameraGeometryState CAM-1g's own [CameraGeometryDiagnosticSnapshot] as of
 *   [capturedAtEpochMillis].
 * @property cameraGeometryStatusTransitionCount CAM-1g's session-scoped status-transition counter.
 * @property cameraGeometryObservedFrameCount CAM-1g's session-scoped observed-frame counter.
 * @property cameraGeometryReadyBundleCount CAM-1g's session-scoped ready-bundle counter.
 * @property cameraIntrinsicsState CAM-2c's full runtime picture ([CameraSessionIntrinsicsDiagnosticState])
 *   - the coordinator state, the typed calibrated-mapping attempt (whatever it was rejected/resolved
 *   for), the published [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution], the raw
 *   [CameraCharacteristicsSnapshot] (camera ID, logical flag, physical camera IDs, pixel/active/
 *   pre-correction arrays, physical sensor size, focal lengths), and the running per-frame transform
 *   counters (all 9 matrix values of the latest frame, matrix class, coordinator frames waited).
 * @property calibrationDiagnostics CAM-2c's calibrated-mapping diagnostics
 *   ([CameraCalibrationDiagnostics]), including the resolved buffer `K`, when this session's calibrated
 *   `AnalysisBuffer` mapping actually succeeded - `null` otherwise (e.g. this Pixel 9 evidence, where
 *   CAM-2c blocks on `UnsupportedLogicalMultiCameraMapping` and only [cameraIntrinsicsState] carries the
 *   root cause).
 * @property rotationDegrees the ready bundle's `CameraFrameMetadata.rotationDegrees`, copied from
 *   [cameraGeometryState] for flat, single-hop access - `null` whenever [cameraGeometryState] itself is
 *   `null` or not `Ready` (see [CameraGeometryDiagnosticSnapshot]'s own KDoc on which category leaves
 *   which fields `null`).
 * @property bufferWidthPx the ready bundle's analysis-buffer width, pixels; see [rotationDegrees].
 * @property bufferHeightPx the ready bundle's analysis-buffer height, pixels; see [rotationDegrees].
 * @property cropRect the ready bundle's source crop rectangle, active-array-local pixels; see
 *   [rotationDegrees] - `null` whenever any one of the four edges is unavailable.
 * @property viewportWidthPx the ready bundle's viewport width, pixels; see [rotationDegrees].
 * @property viewportHeightPx the ready bundle's viewport height, pixels; see [rotationDegrees].
 */
data class CamDiagnosticSnapshot(
    val capturedAtEpochMillis: Long,
    val sessionId: Long,
    val cam2bState: PredictedStarOverlayState?,
    val cameraGeometryState: CameraGeometryDiagnosticSnapshot?,
    val cameraGeometryStatusTransitionCount: Int,
    val cameraGeometryObservedFrameCount: Long,
    val cameraGeometryReadyBundleCount: Long,
    val cameraIntrinsicsState: CameraSessionIntrinsicsDiagnosticState?,
    val calibrationDiagnostics: CameraCalibrationDiagnostics?,
    val rotationDegrees: Int?,
    val bufferWidthPx: Int?,
    val bufferHeightPx: Int?,
    val cropRect: CamDiagnosticCropRect?,
    val viewportWidthPx: Int?,
    val viewportHeightPx: Int?,
)

/**
 * Pure mapper (diagnostic export/freeze fix §1): builds one [CamDiagnosticSnapshot] entirely from
 * already-resolved values - no Android dependency, no live provider/coordinator read of its own, no
 * clock read of its own ([capturedAtEpochMillis] is a parameter, not `System.currentTimeMillis()`
 * called here). Every field this function has to derive (the flattened
 * [CamDiagnosticSnapshot.rotationDegrees]/[CamDiagnosticSnapshot.bufferWidthPx]/etc.) is copied
 * out of [cameraGeometryState] verbatim, never recomputed - so this snapshot can never disagree with
 * the [CameraGeometryDiagnosticSnapshot] it was built from.
 */
fun captureCamDiagnosticSnapshot(
    capturedAtEpochMillis: Long,
    sessionId: Long,
    cam2bState: PredictedStarOverlayState?,
    cameraGeometryState: CameraGeometryDiagnosticSnapshot?,
    cameraGeometryStatusTransitionCount: Int,
    cameraGeometryObservedFrameCount: Long,
    cameraGeometryReadyBundleCount: Long,
    cameraIntrinsicsState: CameraSessionIntrinsicsDiagnosticState?,
    calibrationDiagnostics: CameraCalibrationDiagnostics?,
): CamDiagnosticSnapshot {
    val cropLeft = cameraGeometryState?.cropLeftPx
    val cropTop = cameraGeometryState?.cropTopPx
    val cropRight = cameraGeometryState?.cropRightPx
    val cropBottom = cameraGeometryState?.cropBottomPx
    val cropRect =
        if (cropLeft != null && cropTop != null && cropRight != null && cropBottom != null) {
            CamDiagnosticCropRect(leftPx = cropLeft, topPx = cropTop, rightPx = cropRight, bottomPx = cropBottom)
        } else {
            null
        }
    return CamDiagnosticSnapshot(
        capturedAtEpochMillis = capturedAtEpochMillis,
        sessionId = sessionId,
        cam2bState = cam2bState,
        cameraGeometryState = cameraGeometryState,
        cameraGeometryStatusTransitionCount = cameraGeometryStatusTransitionCount,
        cameraGeometryObservedFrameCount = cameraGeometryObservedFrameCount,
        cameraGeometryReadyBundleCount = cameraGeometryReadyBundleCount,
        cameraIntrinsicsState = cameraIntrinsicsState,
        calibrationDiagnostics = calibrationDiagnostics,
        rotationDegrees = cameraGeometryState?.rotationDegrees,
        bufferWidthPx = cameraGeometryState?.bufferWidthPx,
        bufferHeightPx = cameraGeometryState?.bufferHeightPx,
        cropRect = cropRect,
        viewportWidthPx = cameraGeometryState?.viewportWidthPx,
        viewportHeightPx = cameraGeometryState?.viewportHeightPx,
    )
}
