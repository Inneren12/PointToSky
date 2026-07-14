package dev.pointtosky.mobile.ar.camera.prediction

import java.util.Locale

/**
 * Deterministic, non-locale-sensitive text formatting for [PredictedStarOverlayState] (CAM-2b task
 * §9), mirroring `CameraGeometryDiagnosticFormat.kt`'s existing conventions: every numeric field uses
 * [Locale.ROOT], and the sealed state's own `toString()` is never used directly.
 */
private fun formatWaitingReason(reason: PredictedStarOverlayWaitingReason): String =
    when (reason) {
        is PredictedStarOverlayWaitingReason.GeometryNotReady -> "geometry: ${reason.category.name}"
        PredictedStarOverlayWaitingReason.ObserverLocationUnavailable -> "observer location unavailable"
        PredictedStarOverlayWaitingReason.ObservationTimeUnavailable -> "observation time unavailable"
        PredictedStarOverlayWaitingReason.MagneticDeclinationUnavailable -> "magnetic declination unavailable"
        PredictedStarOverlayWaitingReason.NoStarsSelected -> "no stars selected"
    }

/** Signed, one-decimal milliseconds — always carries an explicit `+`/`-` sign, never bare digits. */
private fun formatPairDeltaMillis(pairDeltaNanos: Long): String =
    String.format(Locale.ROOT, "%+.1f ms", pairDeltaNanos / 1_000_000.0)

private fun formatDeclinationDeg(declinationDeg: Double): String = String.format(Locale.ROOT, "%.1f°", declinationDeg)

/**
 * Deterministic, multi-line overlay text (task §9) — one line per required field group, never one line
 * per star, never the sealed [PredictedStarOverlayState]'s own `toString()`.
 */
fun buildPredictedStarOverlayDiagnosticText(state: PredictedStarOverlayState): String {
    val lines = mutableListOf("CAM-2b predicted overlay")
    when (state) {
        PredictedStarOverlayState.Disabled -> lines += "status: disabled"

        is PredictedStarOverlayState.Waiting -> lines += "status: waiting (${formatWaitingReason(state.reason)})"

        is PredictedStarOverlayState.Unavailable -> lines += "status: unavailable (${state.reason.name})"

        is PredictedStarOverlayState.Ready -> {
            val summary = state.summary
            val metadata = state.metadata
            lines += "status: ready"
            lines += "input stars: ${summary.inputCount}  visible: ${summary.visibleInViewportCount}"
            lines += "behind camera: ${summary.behindCameraCount}  outside image: ${summary.outsideImageCount}"
            lines += "inside image, outside viewport: ${summary.insideImageOutsideViewportCount}"
            lines += "frame: ${metadata.frameTimestampNanos}  rotation: ${metadata.rotationTimestampNanos}"
            lines += "pair Δ: ${formatPairDeltaMillis(metadata.pairDeltaNanos)}"
            lines += "frame rotationDegrees: ${metadata.frameRotationDegrees}°"
            lines += "intrinsics: ${metadata.intrinsicsSource} / ${metadata.intrinsicsReference}"
            lines += "declination: ${formatDeclinationDeg(metadata.magneticDeclinationDeg)}"
        }
    }
    return lines.joinToString(separator = "\n")
}
