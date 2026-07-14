package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import java.util.Locale

/**
 * Deterministic, non-locale-sensitive text formatting for [CameraGeometryDiagnosticSnapshot] (CAM-1g
 * §5). Every numeric field always uses [Locale.ROOT] so a device set to a comma-decimal locale never
 * changes the displayed digits, and every missing field renders the same stable placeholder rather
 * than an empty string or `"null"`.
 */
private const val UNAVAILABLE_PLACEHOLDER = "unavailable"

internal fun formatPixelSize(
    widthPx: Int?,
    heightPx: Int?,
): String = if (widthPx != null && heightPx != null) "$widthPx×$heightPx" else UNAVAILABLE_PLACEHOLDER

internal fun formatCropRect(snapshot: CameraGeometryDiagnosticSnapshot): String {
    val left = snapshot.cropLeftPx
    val top = snapshot.cropTopPx
    val right = snapshot.cropRightPx
    val bottom = snapshot.cropBottomPx
    return if (left != null && top != null && right != null && bottom != null) {
        "[$left,$top — $right,$bottom]"
    } else {
        UNAVAILABLE_PLACEHOLDER
    }
}

internal fun formatRotationDegrees(rotationDegrees: Int?): String =
    rotationDegrees?.let { "$it°" } ?: UNAVAILABLE_PLACEHOLDER

/** Signed, one-decimal milliseconds — always carries an explicit `+`/`-` sign, never bare digits. */
internal fun formatPairDeltaMillis(pairDeltaNanos: Long?): String =
    pairDeltaNanos?.let { String.format(Locale.ROOT, "%+.1f ms", it / 1_000_000.0) } ?: UNAVAILABLE_PLACEHOLDER

internal fun formatFovDeg(
    horizontalFovDeg: Double?,
    verticalFovDeg: Double?,
): String =
    if (horizontalFovDeg != null && verticalFovDeg != null) {
        String.format(Locale.ROOT, "%.1f° x %.1f°", horizontalFovDeg, verticalFovDeg)
    } else {
        UNAVAILABLE_PLACEHOLDER
    }

internal fun formatScale(uniformScale: Double?): String =
    uniformScale?.let { String.format(Locale.ROOT, "%.3f", it) } ?: UNAVAILABLE_PLACEHOLDER

internal fun formatOffset(
    displayOffsetX: Double?,
    displayOffsetY: Double?,
): String =
    if (displayOffsetX != null && displayOffsetY != null) {
        String.format(Locale.ROOT, "%.2f, %.2f", displayOffsetX, displayOffsetY)
    } else {
        UNAVAILABLE_PLACEHOLDER
    }

internal fun formatIntrinsicsSource(source: CameraIntrinsicsSource?): String = source?.name ?: UNAVAILABLE_PLACEHOLDER

internal fun formatCenterProbeLines(centerProbe: CameraGeometryCenterProbeSnapshot?): List<String> =
    if (centerProbe == null) {
        emptyList()
    } else {
        listOf(
            String.format(
                Locale.ROOT,
                "center image: %.1f, %.1f",
                centerProbe.imagePointXPx,
                centerProbe.imagePointYPx,
            ),
            String.format(Locale.ROOT, "round-trip: %.3f px", centerProbe.roundTripErrorPx),
        )
    }

/**
 * Deterministic, multi-line overlay text (CAM-1g §5) — one line per field group, never the sealed
 * `CameraSessionGeometryResult`'s own `toString()`, no matrix values, no pixel-plane data. Every
 * numeric field is formatted with a fixed, non-locale-sensitive decimal separator and precision (see
 * the individual `format*` helpers above).
 */
fun buildCameraGeometryDiagnosticText(
    snapshot: CameraGeometryDiagnosticSnapshot,
    sessionId: Long,
    statusTransitionCount: Int,
    observedFrameCount: Long,
    readyBundleCount: Long,
): String {
    val lines = mutableListOf<String>()
    lines += "CAM geometry: ${snapshot.category.name}"
    lines += "session: $sessionId  transitions: $statusTransitionCount"
    lines += "frames: $observedFrameCount  ready: $readyBundleCount"
    lines += "buffer: ${formatPixelSize(snapshot.bufferWidthPx, snapshot.bufferHeightPx)}"
    lines += "crop: ${formatCropRect(snapshot)}"
    lines += "rotation: ${formatRotationDegrees(snapshot.rotationDegrees)}"
    lines += "viewport: ${formatPixelSize(snapshot.viewportWidthPx, snapshot.viewportHeightPx)}"
    lines += "pair Δ: ${formatPairDeltaMillis(snapshot.pairDeltaNanos)}"
    lines += "intrinsics: ${formatIntrinsicsSource(snapshot.intrinsicsSource)}"
    if (snapshot.horizontalFovDeg != null || snapshot.verticalFovDeg != null) {
        lines += "FOV: ${formatFovDeg(snapshot.horizontalFovDeg, snapshot.verticalFovDeg)}"
    }
    if (snapshot.uniformScale != null) {
        lines += "scale: ${formatScale(snapshot.uniformScale)}"
    }
    if (snapshot.displayOffsetX != null || snapshot.displayOffsetY != null) {
        lines += "offset: ${formatOffset(snapshot.displayOffsetX, snapshot.displayOffsetY)}"
    }
    lines += formatCenterProbeLines(snapshot.centerProbe)
    return lines.joinToString(separator = "\n")
}
