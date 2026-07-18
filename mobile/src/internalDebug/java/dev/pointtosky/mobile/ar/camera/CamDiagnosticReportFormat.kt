package dev.pointtosky.mobile.ar.camera

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** `internalDebug`-only. Placeholder for a field this snapshot did not carry a value for - never a
 * blank string or `"null"`. Kept `private` (not `internal`) deliberately - `CameraSessionIntrinsicsDiagnosticFormat.kt`
 * (`main`) declares its own identically-named `private const val UNAVAILABLE` in the same package; since
 * the `internalDebug` variant compiles `main` and `internalDebug` together as one module, an `internal`
 * (module-visible) constant here would create an ambiguous reference inside that other file's own
 * functions. Two `private` (file-scoped) constants of the same name in the same package never conflict. */
private const val UNAVAILABLE = "unavailable"

/**
 * `internalDebug`-only. Whether a [CamDiagnosticSnapshot] currently on screen is the always-fresh live
 * value or a user-frozen one. A plain, pure enum - never a `Boolean`, so the report text and the HUD
 * banner share one unambiguous vocabulary ("LIVE"/"FROZEN") rather than each inventing its own.
 */
enum class CamDiagnosticLiveness {
    LIVE,
    FROZEN,
}

/** `internalDebug`-only. ISO-8601 UTC instant, e.g. `2026-07-16T12:34:56.789Z` - fixed format, never
 * locale-sensitive. */
internal fun formatCapturedAt(epochMillis: Long): String = DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(epochMillis))

private fun formatPx(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

private fun cam2bReportLines(cam2b: Cam2bDiagnosticSnapshot): List<String> {
    val lines = mutableListOf("status: ${cam2b.status?.lowercase(Locale.ROOT) ?: UNAVAILABLE}")
    cam2b.waitingReason?.let { lines += "waiting reason: $it" }
    cam2b.unavailableReason?.let { lines += "unavailable reason: $it" }
    cam2b.intrinsicsMode?.let { lines += "intrinsics mode: $it" }
    cam2b.inputCount?.let { lines += "input: $it" }
    cam2b.visibleCount?.let { lines += "visible: $it" }
    return lines
}

/** One line per typed attempt shape, mirroring the pre-existing CAM-2c overlay's own convention (never
 * derived from a raw exception message or `toString()`). */
private fun formatAttempt(cam2c: Cam2cDiagnosticSnapshot): String {
    val type = cam2c.attemptType ?: return "not attempted (no analyzed frame yet)"
    return when (type) {
        "UnsupportedSensorToBufferTransform", "RotationOwnershipUnproven" -> "$type(${cam2c.attemptTransformClass ?: UNAVAILABLE})"
        "InvalidMetadata" -> "InvalidMetadata(${cam2c.attemptInvalidMetadataReason ?: UNAVAILABLE})"
        "UnsupportedLogicalMultiCameraMapping" ->
            "UnsupportedLogicalMultiCameraMapping(cameraId=${cam2c.camera.cameraId ?: "unknown"}, " +
                "physicalIds=${cam2c.camera.physicalCameraIds?.joinToString() ?: UNAVAILABLE})"
        else -> type
    }
}

private fun cam2cAttemptLines(cam2c: Cam2cDiagnosticSnapshot): List<String> {
    val lines =
        mutableListOf(
            "coordinator: ${cam2c.coordinatorState ?: UNAVAILABLE}",
            "attempt: ${formatAttempt(cam2c)}",
        )
    cam2c.resolvedBufferK?.let { k ->
        lines += "resolved buffer K:"
        lines += "  fx,fy,cx,cy: ${formatPx(k.fxPx)}, ${formatPx(k.fyPx)}, ${formatPx(k.cxPx)}, ${formatPx(k.cyPx)}"
    }
    return lines
}

private fun publishedIntrinsicsLines(published: PublishedIntrinsicsExportSnapshot): List<String> {
    val lines = mutableListOf("publication: ${published.publication ?: UNAVAILABLE}")
    published.fallbackReason?.let { lines += "fallback reason: $it" }
    lines += "source: ${published.source ?: UNAVAILABLE}"
    val reference =
        if (published.reference == "AnalysisBuffer" && published.referenceBufferWidthPx != null && published.referenceBufferHeightPx != null) {
            "AnalysisBuffer(${published.referenceBufferWidthPx}x${published.referenceBufferHeightPx})"
        } else {
            published.reference ?: UNAVAILABLE
        }
    lines += "reference: $reference"
    lines += "intrinsics quality: ${published.quality ?: UNAVAILABLE}"
    return lines
}

private fun cameraLines(camera: CameraMetadataExportSnapshot): List<String> =
    listOf(
        "id: ${camera.cameraId ?: UNAVAILABLE}",
        "logical: ${camera.logicalMultiCamera ?: UNAVAILABLE}",
        "physical IDs: ${camera.physicalCameraIds?.joinToString() ?: UNAVAILABLE}",
    )

private fun formatMappedBounds(bounds: MappedBoundsExportSnapshot?): String =
    bounds?.let { "[${formatPx(it.leftPx)},${formatPx(it.topPx)} — ${formatPx(it.rightPx)},${formatPx(it.bottomPx)}]" } ?: UNAVAILABLE

/**
 * The raw transform transport for the *latest* frame only - present/matrix/structural class, plus the
 * separate, explicitly-scoped whole-active-array-mapping hypothesis verdict - `class` and
 * `wholeActiveArrayHypothesisVerdict` are deliberately two different lines, never conflated: a matrix can
 * classify as `AXIS_ALIGNED_0` (structurally supported) while the hypothesis verdict is anything other
 * than `MATCHES_WHOLE_ACTIVE_ARRAY_HYPOTHESIS` — that is evidence only that *this one, named* hypothesis
 * does not hold, **never** a claim that the transform itself is broken, invalid, unusable, or known not
 * to describe the real pipeline (this codebase has not source-traced or device-proven the pinned
 * CameraX version's real source-domain contract) — see the real Pixel 9 identity-matrix evidence in
 * `docs/validation/cam_2c_pixel9_evidence.md` and [assessWholeActiveArrayMappingHypothesis]'s own KDoc.
 * `sourceDomainBasis` names exactly which hypothesis was tested. Running counters live in the COUNTERS
 * section instead (see [countersSectionLines]).
 */
private fun frameTransformSectionLines(frameTransform: FrameTransformExportSnapshot): List<String> {
    // Full available precision (dual-basis slice, task §9): Double.toString, never a rounded %f -
    // these are widened android.graphics.Matrix float32 values, and a bit-level comparison against
    // the CameraX 1.4.2 implementation model needs every digit the platform reported. The rounded
    // formatPx rendering remains for bounds lines only, where sub-pixel rounding is presentation.
    val matrixLine =
        frameTransform.matrix?.let { m ->
            "matrix[9]: [${m.joinToString { it.toString() }}]"
        } ?: "matrix[9]: $UNAVAILABLE"
    return listOf(
        "present: ${frameTransform.present}",
        matrixLine,
        "class: ${frameTransform.transformClass ?: UNAVAILABLE}",
        "sourceDomainBasis: ${frameTransform.sourceDomainBasis ?: UNAVAILABLE}",
        "wholeActiveArrayHypothesisVerdict: ${frameTransform.wholeActiveArrayHypothesisVerdict ?: UNAVAILABLE}",
        // Alongside - never replacing - the binary verdict above: which SHAPE the mapped-bounds
        // relationship has (hypothesis-scoped evidence only; see WholeActiveArrayGeometry.kt).
        "wholeActiveArrayGeometryClass: ${frameTransform.wholeActiveArrayGeometryClass ?: UNAVAILABLE}",
        "mappedAssumedSourceBounds: ${formatMappedBounds(frameTransform.mappedAssumedSourceBoundsPx)}",
        "expectedBufferBounds: ${formatMappedBounds(frameTransform.expectedBufferBoundsPx)}",
        "hypothesisReason: ${frameTransform.hypothesisReason ?: UNAVAILABLE}",
        "geometryReason: ${frameTransform.wholeActiveArrayGeometryReason ?: UNAVAILABLE}",
    )
}

private fun metadataLines(camera: CameraMetadataExportSnapshot): List<String> {
    val pixelArray =
        if (camera.pixelArrayWidthPx != null && camera.pixelArrayHeightPx != null) {
            "${camera.pixelArrayWidthPx}x${camera.pixelArrayHeightPx}"
        } else {
            UNAVAILABLE
        }
    val activeRect =
        if (camera.activeArrayLeftPx != null && camera.activeArrayTopPx != null &&
            camera.activeArrayRightPx != null && camera.activeArrayBottomPx != null
        ) {
            "[${camera.activeArrayLeftPx},${camera.activeArrayTopPx} — ${camera.activeArrayRightPx},${camera.activeArrayBottomPx}]"
        } else {
            UNAVAILABLE
        }
    val preCorrectionRect =
        if (camera.preCorrectionActiveArrayLeftPx != null && camera.preCorrectionActiveArrayTopPx != null &&
            camera.preCorrectionActiveArrayRightPx != null && camera.preCorrectionActiveArrayBottomPx != null
        ) {
            "[${camera.preCorrectionActiveArrayLeftPx},${camera.preCorrectionActiveArrayTopPx} — " +
                "${camera.preCorrectionActiveArrayRightPx},${camera.preCorrectionActiveArrayBottomPx}]"
        } else {
            UNAVAILABLE
        }
    val physicalSize =
        if (camera.sensorPhysicalWidthMm != null && camera.sensorPhysicalHeightMm != null) {
            String.format(Locale.ROOT, "%.2fmm x %.2fmm", camera.sensorPhysicalWidthMm, camera.sensorPhysicalHeightMm)
        } else {
            UNAVAILABLE
        }
    val focalLength =
        camera.availableFocalLengthsMm?.takeIf { it.isNotEmpty() }
            ?.joinToString(prefix = "[", postfix = "]") { String.format(Locale.ROOT, "%.2fmm", it) }
            ?: UNAVAILABLE
    return listOf(
        "pixel array: $pixelArray",
        "active rect: $activeRect",
        "pre-correction rect: $preCorrectionRect",
        "physical size: $physicalSize",
        "focal length: $focalLength",
    )
}

private fun bufferGeometryLines(geometry: CameraGeometryExportSnapshot): List<String> {
    val rotation = geometry.rotationDegrees?.let { "$it°" } ?: UNAVAILABLE
    val buffer =
        if (geometry.bufferWidthPx != null && geometry.bufferHeightPx != null) {
            "${geometry.bufferWidthPx}x${geometry.bufferHeightPx}"
        } else {
            UNAVAILABLE
        }
    val crop = geometry.cropRect?.let { "[${it.leftPx},${it.topPx} — ${it.rightPx},${it.bottomPx}]" } ?: UNAVAILABLE
    val viewport =
        if (geometry.viewportWidthPx != null && geometry.viewportHeightPx != null) {
            "${geometry.viewportWidthPx}x${geometry.viewportHeightPx}"
        } else {
            UNAVAILABLE
        }
    return listOf("rotation: $rotation", "buffer: $buffer", "crop: $crop", "viewport: $viewport")
}

private fun calibrationLines(calibration: CameraCalibrationExportSnapshot?): List<String> {
    if (calibration == null) return listOf(UNAVAILABLE)
    val lines = mutableListOf("CAM-2c calibration")
    lines += "active array: ${calibration.activeArrayWidthPx}×${calibration.activeArrayHeightPx}"
    lines +=
        "active rect (full pixel array): [${formatPx(calibration.activeArrayLeftPx)},${formatPx(calibration.activeArrayTopPx)} — " +
            "${formatPx(calibration.activeArrayRightPx)},${formatPx(calibration.activeArrayBottomPx)}]"
    val pixelArrayWidthPx = calibration.pixelArrayWidthPx
    val pixelArrayHeightPx = calibration.pixelArrayHeightPx
    lines +=
        "pixel array: " +
            if (pixelArrayWidthPx != null && pixelArrayHeightPx != null) "${pixelArrayWidthPx}×${pixelArrayHeightPx}" else UNAVAILABLE
    lines += "sensor: ${String.format(Locale.ROOT, "%.2f mm", calibration.sensorWidthMm)} × " +
        String.format(Locale.ROOT, "%.2f mm", calibration.sensorHeightMm)
    lines += "focal length: ${String.format(Locale.ROOT, "%.2f mm", calibration.focalLengthMm)}"
    lines += "active fx/fy: ${formatPx(calibration.activeFxPx)}, ${formatPx(calibration.activeFyPx)}"
    lines += "focal derivation basis: ${calibration.focalDerivationBasis}"
    lines += "principal point basis: ${calibration.principalPointBasis}"
    lines += "active cx/cy: ${formatPx(calibration.activeCxPx)}, ${formatPx(calibration.activeCyPx)}"
    lines +=
        "crop (active-array-local): [${formatPx(calibration.cropLeftPx)},${formatPx(calibration.cropTopPx)} — " +
            "${formatPx(calibration.cropRightPx)},${formatPx(calibration.cropBottomPx)}]"
    lines += "buffer fx/fy: ${formatPx(calibration.bufferFxPx)}, ${formatPx(calibration.bufferFyPx)}"
    lines += "buffer cx/cy: ${formatPx(calibration.bufferCxPx)}, ${formatPx(calibration.bufferCyPx)}"
    lines += "quality: ${calibration.quality}" + (calibration.skewDiagnosticReason?.let { " ($it)" } ?: "")
    lines += "sensor→buffer: ${calibration.sensorToBufferMappingSource} (${calibration.transformClass})"
    lines += "camera id: ${calibration.cameraId ?: "unknown"}, logical: ${calibration.isLogicalMultiCamera}"
    lines += "physical camera ids: ${calibration.physicalCameraIds?.joinToString() ?: UNAVAILABLE}"
    return lines
}

private fun countersSectionLines(snapshot: CamDiagnosticSnapshot): List<String> {
    val ft = snapshot.cam2c.frameTransform
    return listOf(
        "geometry transitions: ${snapshot.geometry.statusTransitionCount}",
        "geometry observed frames: ${snapshot.geometry.observedFrameCount}",
        "geometry ready bundles: ${snapshot.geometry.readyBundleCount}",
        "frames analyzed: ${ft.framesAnalyzed}",
        "frames withTransform: ${ft.framesWithTransform}",
        "frames nullTransform: ${ft.framesWithNullTransform}",
        "frames supportedClassAxisAligned0: ${ft.framesWithSupportedTransformClass}",
        "coordinator frames waited: ${ft.coordinatorFramesWaited}",
    )
}

/**
 * `internalDebug`-only. Builds the complete, deterministic, plain-text CAM diagnostics report - a pure
 * function of [snapshot] and [liveness] only, consuming exclusively the immutable [CamDiagnosticSnapshot]
 * DTO tree (never a runtime provider/coordinator, never a raw Compose semantics-tree scrape). Every
 * numeric field this function formats itself uses [Locale.ROOT].
 *
 * One line per field group under an explicit, uppercase section header - `POINTTOSKY CAM DIAGNOSTICS`,
 * `SESSION`, `CAM-2B`, `CAM-2C ATTEMPT`, `PUBLISHED INTRINSICS`, `CAMERA`, `FRAME TRANSFORM`,
 * `METADATA`, `BUFFER GEOMETRY`, `CALIBRATION`, `COUNTERS` - so the full report never requires
 * scrolling through screenshots to reconstruct.
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
    lines += "geometry category: ${snapshot.geometry.category ?: UNAVAILABLE}"
    lines += ""
    lines += "CAM-2B"
    lines += cam2bReportLines(snapshot.cam2b)
    lines += ""
    lines += "CAM-2C ATTEMPT"
    lines += cam2cAttemptLines(snapshot.cam2c)
    lines += ""
    lines += "PUBLISHED INTRINSICS"
    lines += publishedIntrinsicsLines(snapshot.cam2c.publishedIntrinsics)
    lines += ""
    lines += "CAMERA"
    lines += cameraLines(snapshot.cam2c.camera)
    lines += ""
    lines += "FRAME TRANSFORM"
    lines += frameTransformSectionLines(snapshot.cam2c.frameTransform)
    lines += ""
    lines += "METADATA"
    lines += metadataLines(snapshot.cam2c.camera)
    lines += ""
    lines += "BUFFER GEOMETRY"
    lines += bufferGeometryLines(snapshot.geometry)
    lines += ""
    lines += "CALIBRATION"
    lines += calibrationLines(snapshot.calibration)
    lines += ""
    lines += "COUNTERS"
    lines += countersSectionLines(snapshot)
    return lines.joinToString(separator = "\n")
}

private fun cam2cStatusLabel(cam2c: Cam2cDiagnosticSnapshot): String =
    when {
        cam2c.attemptType == "Resolved" -> "RESOLVED"
        cam2c.attemptType != null -> "BLOCKED"
        cam2c.coordinatorState == null -> "UNAVAILABLE"
        cam2c.coordinatorState == "DISPOSED" -> "DISPOSED"
        else -> "PENDING"
    }

/** A short, human-scannable root-cause phrase for the compact HUD - never the full typed attempt. */
private fun cam2cShortReason(cam2c: Cam2cDiagnosticSnapshot): String? =
    when (cam2c.attemptType) {
        null, "Resolved" -> null
        "MissingActiveArray" -> "missing active array"
        "MissingPhysicalSensorSize" -> "missing physical sensor size"
        "MissingPixelArraySize" -> "missing pixel array size"
        "MissingFocalLength" -> "missing focal length"
        "MissingSensorToBufferTransform" -> "missing sensor-to-buffer transform"
        "UnsupportedSensorToBufferTransform" -> "unsupported transform (${cam2c.attemptTransformClass ?: UNAVAILABLE})"
        "RotationOwnershipUnproven" -> "rotation ownership unproven (${cam2c.attemptTransformClass ?: UNAVAILABLE})"
        "UnsupportedLogicalMultiCameraMapping" -> "logical multi-camera"
        "InvalidMetadata" -> "invalid metadata (${cam2c.attemptInvalidMetadataReason ?: UNAVAILABLE})"
        else -> null
    }

/**
 * `internalDebug`-only. Builds the compact HUD's highest-value summary - a handful of lines naming the
 * CAM-2c root cause directly. Pure function of [snapshot]; the full report lives behind "Open
 * diagnostics" ([buildCamDiagnosticReportText]).
 */
fun buildCamDiagnosticCompactSummaryText(snapshot: CamDiagnosticSnapshot): String {
    val cam2c = snapshot.cam2c
    val lines = mutableListOf<String>()
    lines += "CAM-2c: ${cam2cStatusLabel(cam2c)}"
    cam2cShortReason(cam2c)?.let { lines += "reason: $it" }
    lines += "camera: ${cam2c.camera.cameraId ?: UNAVAILABLE}"
    lines += "physical: ${cam2c.camera.physicalCameraIds?.joinToString() ?: UNAVAILABLE}"
    lines += "matrix: ${cam2c.frameTransform.transformClass ?: UNAVAILABLE} · " +
        "${cam2c.frameTransform.framesWithSupportedTransformClass}/${cam2c.frameTransform.framesAnalyzed}"
    lines += "published: ${cam2c.publishedIntrinsics.reference ?: UNAVAILABLE}"
    return lines.joinToString(separator = "\n")
}
