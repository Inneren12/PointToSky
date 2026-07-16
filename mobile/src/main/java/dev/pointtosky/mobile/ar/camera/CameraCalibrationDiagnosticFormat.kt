package dev.pointtosky.mobile.ar.camera

import java.util.Locale

/**
 * Deterministic, non-locale-sensitive text formatting for [CameraCalibrationDiagnostics] (CAM-2c
 * §9), mirroring `CameraGeometryDiagnosticFormat.kt`'s own conventions: every numeric field uses
 * [Locale.ROOT].
 */
private fun formatPx(value: Double): String = String.format(Locale.ROOT, "%.1f", value)

private fun formatMm(value: Double): String = String.format(Locale.ROOT, "%.2f mm", value)

/**
 * Deterministic, multi-line overlay text (CAM-2c §9) — one line per field group. Only ever built
 * from a successful [resolveAnalysisBufferIntrinsics] result (a non-null [CameraCalibrationDiagnostics]),
 * so every field is always available — unlike [buildCameraGeometryDiagnosticText], there is no
 * partial/unavailable state to render here.
 */
fun buildCameraCalibrationDiagnosticText(diagnostics: CameraCalibrationDiagnostics): String {
    val lines = mutableListOf("CAM-2c calibration")
    lines += "active array: ${diagnostics.activeArrayWidthPx}×${diagnostics.activeArrayHeightPx}"
    lines +=
        "active rect (full pixel array): [${formatPx(diagnostics.activeArrayLeftPx)},${formatPx(diagnostics.activeArrayTopPx)} — " +
            "${formatPx(diagnostics.activeArrayRightPx)},${formatPx(diagnostics.activeArrayBottomPx)}]"
    val pixelArrayWidthPx = diagnostics.pixelArrayWidthPx
    val pixelArrayHeightPx = diagnostics.pixelArrayHeightPx
    lines +=
        "pixel array: " +
            if (pixelArrayWidthPx != null && pixelArrayHeightPx != null) {
                "${pixelArrayWidthPx}×${pixelArrayHeightPx}"
            } else {
                "unavailable"
            }
    lines +=
        "pixel-array vs active-array delta: " +
            if (pixelArrayWidthPx != null && pixelArrayHeightPx != null) {
                "Δw=${pixelArrayWidthPx - diagnostics.activeArrayWidthPx}, " +
                    "Δh=${pixelArrayHeightPx - diagnostics.activeArrayHeightPx}"
            } else {
                "unavailable"
            }
    lines += "sensor: ${formatMm(diagnostics.sensorWidthMm)} × ${formatMm(diagnostics.sensorHeightMm)}"
    lines += "focal length: ${formatMm(diagnostics.focalLengthMm)}"
    lines += "active fx/fy: ${formatPx(diagnostics.activeFxPx)}, ${formatPx(diagnostics.activeFyPx)}"
    lines += "focal derivation basis: ${diagnostics.focalDerivationBasis}"
    lines += "principal point basis: ${diagnostics.principalPointBasis}"
    lines += "active cx/cy: ${formatPx(diagnostics.activeCxPx)}, ${formatPx(diagnostics.activeCyPx)}"
    lines +=
        "crop (active-array-local): [${formatPx(diagnostics.cropLeftPx)},${formatPx(diagnostics.cropTopPx)} — " +
            "${formatPx(diagnostics.cropRightPx)},${formatPx(diagnostics.cropBottomPx)}]"
    lines += "buffer fx/fy: ${formatPx(diagnostics.bufferFxPx)}, ${formatPx(diagnostics.bufferFyPx)}"
    lines += "buffer cx/cy: ${formatPx(diagnostics.bufferCxPx)}, ${formatPx(diagnostics.bufferCyPx)}"
    lines += "quality: ${diagnostics.quality.name}" + (diagnostics.skewDiagnosticReason?.let { " ($it)" } ?: "")
    lines += "sensor→buffer: ${diagnostics.sensorToBufferMappingSource} (${diagnostics.transformClass.name})"
    lines += "camera id: ${diagnostics.cameraId ?: "unknown"}, logical: ${diagnostics.isLogicalMultiCamera}"
    lines += "physical camera ids: ${diagnostics.physicalCameraIds?.sorted()?.joinToString() ?: "unavailable"}"
    return lines.joinToString(separator = "\n")
}
