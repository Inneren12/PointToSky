package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.mobile.ar.camera.prediction.PredictedStarOverlayState
import dev.pointtosky.mobile.ar.camera.prediction.name
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Whether a [CamDiagnosticSnapshot] currently on screen is the always-fresh live value or a
 * user-frozen one (diagnostic export/freeze fix §2). A plain, pure enum - never a `Boolean`, so the
 * report text and the HUD banner share one unambiguous vocabulary ("LIVE"/"FROZEN") rather than each
 * inventing its own.
 */
enum class CamDiagnosticLiveness {
    LIVE,
    FROZEN,
}

/** ISO-8601 UTC instant, e.g. `2026-07-16T12:34:56.789Z` - fixed format, never locale-sensitive. */
internal fun formatCapturedAt(epochMillis: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))

/** Matches `CameraSessionIntrinsicsDiagnosticFormat.kt`/`CameraCalibrationDiagnosticFormat.kt`'s own
 * identical, deliberately-duplicated per-file `formatPx` - see that file's own KDoc. */
private fun formatPx(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

private fun cam2bReportLines(state: PredictedStarOverlayState?): List<String> =
    when (state) {
        null -> listOf("status: $UNAVAILABLE")
        PredictedStarOverlayState.Disabled -> listOf("status: disabled")
        is PredictedStarOverlayState.Waiting ->
            listOf(
                "status: waiting",
                "waiting reason: ${state.reason.name}",
            )
        is PredictedStarOverlayState.Unavailable ->
            listOf(
                "status: unavailable",
                "unavailable reason: ${state.reason.name}",
            )
        is PredictedStarOverlayState.Ready ->
            listOf(
                "status: ready",
                "intrinsics mode: ${state.metadata.intrinsicsMode.name}",
                "input: ${state.metadata.inputCount}",
                "visible: ${state.metadata.visibleCount}",
            )
    }

/** [CameraSessionIntrinsicsDiagnosticState.coordinatorState] + the typed attempt, plus the resolved
 * buffer `K` (via [resolvedBufferKLines]) whenever the attempt actually succeeded - never omitted when
 * the attempt failed, so a reader always sees *why* CAM-2c was rejected (see [formatAnalysisBufferAttempt]). */
private fun cam2cAttemptLines(state: CameraSessionIntrinsicsDiagnosticState?): List<String> {
    if (state == null) {
        return listOf(
            "coordinator: $UNAVAILABLE",
            "attempt: ${formatAnalysisBufferAttempt(null)}",
        )
    }
    val lines =
        mutableListOf(
            "coordinator: ${state.coordinatorState.name}",
            "attempt: ${formatAnalysisBufferAttempt(state.analysisBufferAttempt)}",
        )
    val resolved = state.analysisBufferAttempt as? AnalysisBufferIntrinsicsResolution.Resolved
    if (resolved != null) {
        lines += "resolved buffer K:"
        lines += resolvedBufferKLines(resolved)
    }
    return lines
}

/** The raw transform transport for the *latest* frame only - present/matrix/class. Running counters
 * ("how many frames total") live in the COUNTERS section instead (see [countersSectionLines]). */
private fun frameTransformSectionLines(state: CameraSessionIntrinsicsDiagnosticState?): List<String> {
    val transform = state?.frameCounters?.latestFrameTransform
    val matrixLine =
        if (transform == null) {
            "matrix[9]: $UNAVAILABLE"
        } else {
            "matrix[9]: [${formatPx(transform.m00)},${formatPx(transform.m01)},${formatPx(transform.m02)}, " +
                "${formatPx(transform.m10)},${formatPx(transform.m11)},${formatPx(transform.m12)}, " +
                "${formatPx(transform.m20)},${formatPx(transform.m21)},${formatPx(transform.m22)}]"
        }
    return listOf(
        "present: ${transform != null}",
        matrixLine,
        "class: ${state?.frameCounters?.latestFrameTransformClass?.name ?: UNAVAILABLE}",
    )
}

private fun bufferGeometryLines(snapshot: CamDiagnosticSnapshot): List<String> {
    val rotation = snapshot.rotationDegrees?.let { "$it°" } ?: UNAVAILABLE
    val buffer =
        if (snapshot.bufferWidthPx != null && snapshot.bufferHeightPx != null) {
            "${snapshot.bufferWidthPx}x${snapshot.bufferHeightPx}"
        } else {
            UNAVAILABLE
        }
    val crop =
        snapshot.cropRect?.let { "[${it.leftPx},${it.topPx} — ${it.rightPx},${it.bottomPx}]" } ?: UNAVAILABLE
    val viewport =
        if (snapshot.viewportWidthPx != null && snapshot.viewportHeightPx != null) {
            "${snapshot.viewportWidthPx}x${snapshot.viewportHeightPx}"
        } else {
            UNAVAILABLE
        }
    return listOf(
        "rotation: $rotation",
        "buffer: $buffer",
        "crop: $crop",
        "viewport: $viewport",
    )
}

/** CAM-1g's own session-scoped counters, plus CAM-2c's per-frame transform-transport counters
 * (distinct from [frameTransformSectionLines], which only describes the *latest* frame). Every field
 * defaults to `0` (never a blank/`unavailable` placeholder) when [CamDiagnosticSnapshot.cameraIntrinsicsState]
 * is `null` - counts of "zero frames observed" is itself meaningful, unlike a scalar field with no
 * defined zero. */
private fun countersSectionLines(snapshot: CamDiagnosticSnapshot): List<String> {
    val counters = snapshot.cameraIntrinsicsState?.frameCounters
    return listOf(
        "geometry transitions: ${snapshot.cameraGeometryStatusTransitionCount}",
        "geometry observed frames: ${snapshot.cameraGeometryObservedFrameCount}",
        "geometry ready bundles: ${snapshot.cameraGeometryReadyBundleCount}",
        "frames analyzed: ${counters?.framesAnalyzed ?: 0}",
        "frames withTransform: ${counters?.framesWithTransform ?: 0}",
        "frames nullTransform: ${counters?.framesWithNullTransform ?: 0}",
        "frames usableAxisAligned0: ${counters?.framesWithUsableTransform ?: 0}",
        "coordinator frames waited: ${counters?.coordinatorFramesWaited ?: 0}",
    )
}

/**
 * Builds the complete, deterministic, plain-text CAM diagnostics report (diagnostic export/freeze fix
 * §3) - a pure function of [snapshot] and [liveness] only, never reading a live provider/coordinator or
 * scraping Compose semantics nodes. Every numeric field this function itself formats uses
 * [Locale.ROOT] (via [formatPx], reused from [CameraSessionIntrinsicsDiagnosticFormat.kt]'s own
 * `internal` helpers, and [buildCameraCalibrationDiagnosticText]/[formatCapturedAt] which do the same),
 * so a device set to a comma-decimal locale never changes the digits.
 *
 * One line per field group under an explicit, uppercase section header - `POINTTOSKY CAM DIAGNOSTICS`,
 * `SESSION`, `CAM-2B`, `CAM-2C ATTEMPT`, `PUBLISHED INTRINSICS`, `CAMERA`, `FRAME TRANSFORM`,
 * `METADATA`, `BUFFER GEOMETRY`, `CALIBRATION`, `COUNTERS` - so the full report never requires
 * scrolling through screenshots to reconstruct: this single string *is* the complete report, meant to
 * be copied/shared/read in one piece.
 */
fun buildCamDiagnosticReportText(
    snapshot: CamDiagnosticSnapshot,
    liveness: CamDiagnosticLiveness,
): String {
    val lines = mutableListOf<String>()
    lines += "POINTTOSKY CAM DIAGNOSTICS"
    lines += "diagnostics: ${liveness.name}"
    lines += "capturedAtEpochMillis: ${snapshot.capturedAtEpochMillis}"
    lines += "capturedAt: ${formatCapturedAt(snapshot.capturedAtEpochMillis)}"
    lines += ""
    lines += "SESSION"
    lines += "session: ${snapshot.sessionId}"
    lines += "geometry category: ${snapshot.cameraGeometryState?.category?.name ?: UNAVAILABLE}"
    lines += ""
    lines += "CAM-2B"
    lines += cam2bReportLines(snapshot.cam2bState)
    lines += ""
    lines += "CAM-2C ATTEMPT"
    lines += cam2cAttemptLines(snapshot.cameraIntrinsicsState)
    lines += ""
    lines += "PUBLISHED INTRINSICS"
    lines += publishedIntrinsicsLines(snapshot.cameraIntrinsicsState?.publishedIntrinsicsResolution)
    lines += ""
    lines += "CAMERA"
    lines += cameraLines(snapshot.cameraIntrinsicsState?.cameraCharacteristicsSnapshot)
    lines += ""
    lines += "FRAME TRANSFORM"
    lines += frameTransformSectionLines(snapshot.cameraIntrinsicsState)
    lines += ""
    lines += "METADATA"
    lines += metadataLines(snapshot.cameraIntrinsicsState?.cameraCharacteristicsSnapshot)
    lines += ""
    lines += "BUFFER GEOMETRY"
    lines += bufferGeometryLines(snapshot)
    lines += ""
    lines += "CALIBRATION"
    lines +=
        if (snapshot.calibrationDiagnostics != null) {
            buildCameraCalibrationDiagnosticText(snapshot.calibrationDiagnostics).lines()
        } else {
            listOf(UNAVAILABLE)
        }
    lines += ""
    lines += "COUNTERS"
    lines += countersSectionLines(snapshot)
    return lines.joinToString(separator = "\n")
}

private fun cam2cStatusLabel(state: CameraSessionIntrinsicsDiagnosticState?): String {
    if (state == null) return "UNAVAILABLE"
    val attempt = state.analysisBufferAttempt
    return when {
        attempt is AnalysisBufferIntrinsicsResolution.Resolved -> "RESOLVED"
        attempt != null -> "BLOCKED"
        state.coordinatorState == CameraSessionIntrinsicsCoordinatorState.DISPOSED -> "DISPOSED"
        else -> "PENDING"
    }
}

/** A short, human-scannable root-cause phrase for the compact HUD - never the full typed attempt
 * (that belongs in the full report's CAM-2C ATTEMPT section, via [formatAnalysisBufferAttempt]).
 * `null` for [AnalysisBufferIntrinsicsResolution.Resolved] and a not-yet-attempted `null` attempt -
 * neither has a "reason" to show. */
private fun cam2cShortReason(attempt: AnalysisBufferIntrinsicsResolution?): String? =
    when (attempt) {
        null -> null
        is AnalysisBufferIntrinsicsResolution.Resolved -> null
        AnalysisBufferIntrinsicsResolution.MissingActiveArray -> "missing active array"
        AnalysisBufferIntrinsicsResolution.MissingPhysicalSensorSize -> "missing physical sensor size"
        AnalysisBufferIntrinsicsResolution.MissingPixelArraySize -> "missing pixel array size"
        AnalysisBufferIntrinsicsResolution.MissingFocalLength -> "missing focal length"
        AnalysisBufferIntrinsicsResolution.MissingSensorToBufferTransform -> "missing sensor-to-buffer transform"
        is AnalysisBufferIntrinsicsResolution.UnsupportedSensorToBufferTransform ->
            "unsupported transform (${attempt.transformClass.name})"
        is AnalysisBufferIntrinsicsResolution.RotationOwnershipUnproven ->
            "rotation ownership unproven (${attempt.transformClass.name})"
        is AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping -> "logical multi-camera"
        is AnalysisBufferIntrinsicsResolution.InvalidMetadata -> "invalid metadata (${attempt.reason})"
    }

/**
 * Builds the compact HUD's highest-value summary (HUD redesign §6) - a handful of lines naming the
 * CAM-2c root cause directly, never the single giant multi-block text the previous HUD required
 * scrolling/multiple screenshots to read. Pure function of [snapshot]; the full report (all sections)
 * lives behind "Open diagnostics" ([buildCamDiagnosticReportText]).
 */
fun buildCamDiagnosticCompactSummaryText(snapshot: CamDiagnosticSnapshot): String {
    val state = snapshot.cameraIntrinsicsState
    val attempt = state?.analysisBufferAttempt
    val cameraSnapshot = state?.cameraCharacteristicsSnapshot
    val counters = state?.frameCounters
    val lines = mutableListOf<String>()
    lines += "CAM-2c: ${cam2cStatusLabel(state)}"
    cam2cShortReason(attempt)?.let { lines += "reason: $it" }
    lines += "camera: ${cameraSnapshot?.cameraId ?: UNAVAILABLE}"
    lines += "physical: ${cameraSnapshot?.physicalCameraIds?.sorted()?.joinToString() ?: UNAVAILABLE}"
    val matrixClass = counters?.latestFrameTransformClass?.name ?: UNAVAILABLE
    lines += "matrix: $matrixClass · ${counters?.framesWithUsableTransform ?: 0}/${counters?.framesAnalyzed ?: 0}"
    val publishedReference = state?.publishedIntrinsicsResolution?.intrinsics?.reference
    lines += "published: ${publishedReference?.let { formatReference(it) } ?: UNAVAILABLE}"
    return lines.joinToString(separator = "\n")
}
