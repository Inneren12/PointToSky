package dev.pointtosky.mobile.ar.camera

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only). A lightweight, decoupled-from-
 * Android-resolver camera matrix used by [projectObjectPoint]/pose solving — deliberately independent of
 * the heavier [CameraCalibrationDiagnostics]/[resolveAnalysisBufferIntrinsics] machinery so the
 * projection/residual pure math is directly unit-testable from small hand-built fixtures, without
 * constructing a full resolver call chain in every test. Production code builds this from a real
 * [CameraCalibrationDiagnostics] via [toFrameContentHypothesisIntrinsics].
 *
 * `activeFxPx`/`activeFyPx`/`activeCxPx`/`activeCyPx` are the physical camera's own pinhole intrinsics
 * in its native active-array-local pixel basis (task §3 "input K basis"); `bufferFxPx`/`bufferFyPx`/
 * `bufferCxPx`/`bufferCyPx` are the same physical calibration mapped into one hypothesis's own
 * `ImageAnalysis`-buffer coordinate space (task §3 "why the output is expected to be in ImageProxy
 * buffer coordinates") — always by composing the active-array intrinsics with a 3x3 sensor-to-buffer
 * matrix (either the real observed CameraX matrix, or a freshly built CameraX-1.4.2-style model — see
 * [FrameContentMappingHypothesisId]), never by any other formula.
 */
internal data class FrameContentHypothesisIntrinsics(
    val activeFxPx: Double,
    val activeFyPx: Double,
    val activeCxPx: Double,
    val activeCyPx: Double,
    val activeArrayWidthPx: Int,
    val activeArrayHeightPx: Int,
    val bufferFxPx: Double,
    val bufferFyPx: Double,
    val bufferCxPx: Double,
    val bufferCyPx: Double,
    val bufferWidthPx: Int,
    val bufferHeightPx: Int,
)

/** Builds the pure [FrameContentHypothesisIntrinsics] this experiment's math needs from a real, already
 * -resolved [CameraCalibrationDiagnostics] — the one place production code crosses from the resolver's
 * DTO into this experiment's own lightweight type. [bufferWidthPx]/[bufferHeightPx] are threaded through
 * explicitly (never re-derived) since [CameraCalibrationDiagnostics] itself does not store the buffer's
 * own dimensions, only its derived fx/fy/cx/cy — the caller already knows them, since they were the
 * exact `bufferWidthPx`/`bufferHeightPx` arguments passed to `resolveAnalysisBufferIntrinsics`. */
internal fun CameraCalibrationDiagnostics.toFrameContentHypothesisIntrinsics(
    bufferWidthPx: Int,
    bufferHeightPx: Int,
): FrameContentHypothesisIntrinsics =
    FrameContentHypothesisIntrinsics(
        activeFxPx = activeFxPx,
        activeFyPx = activeFyPx,
        activeCxPx = activeCxPx,
        activeCyPx = activeCyPx,
        activeArrayWidthPx = activeArrayWidthPx,
        activeArrayHeightPx = activeArrayHeightPx,
        bufferFxPx = bufferFxPx,
        bufferFyPx = bufferFyPx,
        bufferCxPx = bufferCxPx,
        bufferCyPx = bufferCyPx,
        bufferWidthPx = bufferWidthPx,
        bufferHeightPx = bufferHeightPx,
    )
