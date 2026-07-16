package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution as CoreCameraIntrinsicsResolution
import java.util.Locale

/**
 * Deterministic, non-locale-sensitive text formatting for [CameraSessionIntrinsicsDiagnosticState]
 * (CAM-2c runtime integration fix §5) — always renders the CAM-2c coordinator state and typed attempt,
 * regardless of whether that attempt succeeded, so a physical-device tester never sees only the
 * downstream CAM-2b `PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED` symptom with no CAM-2c root cause
 * (see this fix's own requirements doc §6/§8).
 */
private const val UNAVAILABLE = "unavailable"

private fun formatPx(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

/**
 * One line per [AnalysisBufferIntrinsicsResolution] variant, always including the fields that
 * distinguish it from every other variant of the same broad shape (e.g. which
 * [dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass] a rejected transform
 * classified as, or which camera ID/physical-camera-IDs a logical-multi-camera rejection named).
 * `null` renders as "not attempted (no analyzed frame yet)" — distinct from every failure variant,
 * since it means CAM-2c was never even tried, not that it ran and failed.
 */
internal fun formatAnalysisBufferAttempt(attempt: AnalysisBufferIntrinsicsResolution?): String =
    when (attempt) {
        null -> "not attempted (no analyzed frame yet)"
        is AnalysisBufferIntrinsicsResolution.Resolved -> "Resolved"
        AnalysisBufferIntrinsicsResolution.MissingActiveArray -> "MissingActiveArray"
        AnalysisBufferIntrinsicsResolution.MissingPhysicalSensorSize -> "MissingPhysicalSensorSize"
        AnalysisBufferIntrinsicsResolution.MissingPixelArraySize -> "MissingPixelArraySize"
        AnalysisBufferIntrinsicsResolution.MissingFocalLength -> "MissingFocalLength"
        AnalysisBufferIntrinsicsResolution.MissingSensorToBufferTransform -> "MissingSensorToBufferTransform"
        is AnalysisBufferIntrinsicsResolution.UnsupportedSensorToBufferTransform ->
            "UnsupportedSensorToBufferTransform(${attempt.transformClass.name})"
        is AnalysisBufferIntrinsicsResolution.RotationOwnershipUnproven ->
            "RotationOwnershipUnproven(${attempt.transformClass.name})"
        is AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping ->
            "UnsupportedLogicalMultiCameraMapping(cameraId=${attempt.cameraId ?: "unknown"}, " +
                "physicalIds=${attempt.physicalCameraIdsForDiagnostics?.sorted()?.joinToString() ?: UNAVAILABLE})"
        is AnalysisBufferIntrinsicsResolution.InvalidMetadata -> "InvalidMetadata(${attempt.reason})"
    }

private fun formatReference(reference: CameraIntrinsicsReference): String =
    when (reference) {
        is CameraIntrinsicsReference.AnalysisBuffer -> "AnalysisBuffer(${reference.widthPx}x${reference.heightPx})"
        CameraIntrinsicsReference.PhysicalSensor -> "PhysicalSensor"
        CameraIntrinsicsReference.Unspecified -> "Unspecified"
    }

/**
 * Renders the *publication* status (whether/why this session's [CoreCameraIntrinsicsResolution]
 * resolved directly or fell back to the legacy fixed-FOV default) and the *intrinsic calibration
 * quality* ([dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality]) as distinct,
 * separately-labelled fields (CAM-2c runtime integration fix P2) — a prior revision reused one
 * `quality:` line for both concerns, rendering the sealed subtype name (`Resolved`/`LegacyFallback`)
 * as if it were the calibration quality and hiding whether CAM-2c actually achieved
 * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality.CALIBRATED] or only
 * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT].
 * `intrinsics quality` is always [dev.pointtosky.core.astro.projection.camera.CameraIntrinsics.quality]
 * itself (`"unavailable"` when `null` — always the case for a `PhysicalSensor`/`LEGACY_FALLBACK`
 * publication, see that type's own cross-field contract), never inferred from [resolution]'s subtype.
 */
private fun publishedIntrinsicsLines(resolution: CoreCameraIntrinsicsResolution?): List<String> {
    if (resolution == null) {
        return listOf(
            "  publication: $UNAVAILABLE",
            "  source: $UNAVAILABLE",
            "  reference: $UNAVAILABLE",
            "  intrinsics quality: $UNAVAILABLE",
        )
    }
    val intrinsics = resolution.intrinsics
    val lines = mutableListOf<String>()
    when (resolution) {
        is CoreCameraIntrinsicsResolution.Resolved -> lines += "  publication: Resolved"
        is CoreCameraIntrinsicsResolution.LegacyFallback -> {
            lines += "  publication: LegacyFallback"
            lines += "  fallback reason: ${resolution.reason}"
        }
    }
    lines += "  source: ${intrinsics.source.name}"
    lines += "  reference: ${formatReference(intrinsics.reference)}"
    lines += "  intrinsics quality: ${intrinsics.quality?.name ?: UNAVAILABLE}"
    return lines
}

private fun cameraLines(snapshot: CameraCharacteristicsSnapshot?): List<String> =
    if (snapshot == null) {
        listOf("  id: $UNAVAILABLE", "  logical: $UNAVAILABLE", "  physical IDs: $UNAVAILABLE")
    } else {
        listOf(
            "  id: ${snapshot.cameraId ?: UNAVAILABLE}",
            "  logical: ${snapshot.isLogicalMultiCamera}",
            "  physical IDs: ${snapshot.physicalCameraIds?.sorted()?.joinToString() ?: UNAVAILABLE}",
        )
    }

private fun frameTransformLines(counters: CameraSessionIntrinsicsFrameCounters): List<String> {
    val transform = counters.latestFrameTransform
    val matrixLine =
        if (transform == null) {
            "  matrix: $UNAVAILABLE"
        } else {
            "  matrix[9]: [${formatPx(transform.m00)},${formatPx(transform.m01)},${formatPx(transform.m02)}, " +
                "${formatPx(transform.m10)},${formatPx(transform.m11)},${formatPx(transform.m12)}, " +
                "${formatPx(transform.m20)},${formatPx(transform.m21)},${formatPx(transform.m22)}]"
        }
    return listOf(
        "  present: ${transform != null}",
        matrixLine,
        "  class: ${counters.latestFrameTransformClass?.name ?: UNAVAILABLE}",
        "  analyzed: ${counters.framesAnalyzed}, withTransform: ${counters.framesWithTransform}, " +
            "nullTransform: ${counters.framesWithNullTransform}, usableAxisAligned0: ${counters.framesWithUsableTransform}",
        "  coordinator frames waited: ${counters.coordinatorFramesWaited}",
    )
}

private fun metadataLines(snapshot: CameraCharacteristicsSnapshot?): List<String> {
    if (snapshot == null) {
        return listOf(
            "  pixel array: $UNAVAILABLE",
            "  active rect: $UNAVAILABLE",
            "  pre-correction rect: $UNAVAILABLE",
            "  physical size: $UNAVAILABLE",
            "  focal length: $UNAVAILABLE",
        )
    }
    val pixelArray =
        if (snapshot.pixelArrayWidthPx != null && snapshot.pixelArrayHeightPx != null) {
            "${snapshot.pixelArrayWidthPx}x${snapshot.pixelArrayHeightPx}"
        } else {
            UNAVAILABLE
        }
    val activeRect =
        if (snapshot.activeArrayLeftPx != null && snapshot.activeArrayTopPx != null &&
            snapshot.activeArrayRightPx != null && snapshot.activeArrayBottomPx != null
        ) {
            "[${snapshot.activeArrayLeftPx},${snapshot.activeArrayTopPx} — " +
                "${snapshot.activeArrayRightPx},${snapshot.activeArrayBottomPx}]"
        } else {
            UNAVAILABLE
        }
    val preCorrectionRect =
        if (snapshot.preCorrectionActiveArrayLeftPx != null && snapshot.preCorrectionActiveArrayTopPx != null &&
            snapshot.preCorrectionActiveArrayRightPx != null && snapshot.preCorrectionActiveArrayBottomPx != null
        ) {
            "[${snapshot.preCorrectionActiveArrayLeftPx},${snapshot.preCorrectionActiveArrayTopPx} — " +
                "${snapshot.preCorrectionActiveArrayRightPx},${snapshot.preCorrectionActiveArrayBottomPx}]"
        } else {
            UNAVAILABLE
        }
    val physicalSize =
        if (snapshot.sensorPhysicalWidthMm != null && snapshot.sensorPhysicalHeightMm != null) {
            String.format(Locale.ROOT, "%.2fmm x %.2fmm", snapshot.sensorPhysicalWidthMm, snapshot.sensorPhysicalHeightMm)
        } else {
            UNAVAILABLE
        }
    val focalLengths = snapshot.availableFocalLengthsMm
    val focalLength =
        if (focalLengths != null && focalLengths.isNotEmpty()) {
            focalLengths.joinToString(prefix = "[", postfix = "]") { String.format(Locale.ROOT, "%.2fmm", it) }
        } else {
            UNAVAILABLE
        }
    return listOf(
        "  pixel array: $pixelArray",
        "  active rect: $activeRect",
        "  pre-correction rect: $preCorrectionRect",
        "  physical size: $physicalSize",
        "  focal length: $focalLength",
    )
}

private fun resolvedBufferKLines(resolved: AnalysisBufferIntrinsicsResolution.Resolved): List<String> {
    val d = resolved.diagnostics
    val reference = resolved.intrinsics.reference
    val bufferLabel =
        if (reference is CameraIntrinsicsReference.AnalysisBuffer) {
            "AnalysisBuffer(${reference.widthPx},${reference.heightPx})"
        } else {
            UNAVAILABLE
        }
    return listOf(
        "  fx,fy,cx,cy: ${formatPx(d.bufferFxPx)}, ${formatPx(d.bufferFyPx)}, ${formatPx(d.bufferCxPx)}, ${formatPx(d.bufferCyPx)}",
        "  $bufferLabel",
    )
}

/**
 * Builds the always-visible CAM-2c runtime-integration diagnostic text (fix §5): coordinator state,
 * the exact typed attempt, the published intrinsics, the bound camera's identity, the latest frame's
 * transform transport, the raw characteristics metadata, and — only when [state]'s attempt actually
 * resolved — the resolved buffer `K`. Never omits the CAM-2c attempt line when resolution fails (fix
 * §5/§6/§8's central acceptance requirement).
 */
fun buildCameraSessionIntrinsicsDiagnosticText(state: CameraSessionIntrinsicsDiagnosticState): String {
    val lines = mutableListOf<String>()
    lines += "CAM-2c coordinator: ${state.coordinatorState.name}"
    lines += "CAM-2c attempt: ${formatAnalysisBufferAttempt(state.analysisBufferAttempt)}"
    lines += "published intrinsics:"
    lines += publishedIntrinsicsLines(state.publishedIntrinsicsResolution)
    lines += "camera:"
    lines += cameraLines(state.cameraCharacteristicsSnapshot)
    lines += "frame transform:"
    lines += frameTransformLines(state.frameCounters)
    lines += "metadata:"
    lines += metadataLines(state.cameraCharacteristicsSnapshot)
    val resolved = state.analysisBufferAttempt as? AnalysisBufferIntrinsicsResolution.Resolved
    if (resolved != null) {
        lines += "resolved buffer K:"
        lines += resolvedBufferKLines(resolved)
    }
    return lines.joinToString(separator = "\n")
}
