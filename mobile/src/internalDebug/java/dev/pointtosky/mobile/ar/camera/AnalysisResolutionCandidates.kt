package dev.pointtosky.mobile.ar.camera

import kotlin.math.abs

/**
 * `internalDebug`-only. CAM-2c dual-basis experiment: deliberate `ImageAnalysis` resolution
 * candidates (task §11). The dual-basis diagnostic needs at least two *meaningfully different aspect
 * ratios* — for a 4:3-ish active array, the traced CameraX 1.4.2 model predicts a ~0.94 px/side
 * vertical crop for a 4:3 640×480 buffer but a ~121.88 px/side vertical crop for a 16:9 1280×720
 * buffer, a sharp, falsifiable discriminator. Nothing here assumes those exact sizes exist: candidates
 * are selected from the sizes the device actually declares
 * (`CameraTopologyEntry.imageAnalysisStreamConfigurationsPx`, read from the real
 * `StreamConfigurationMap` for `YUV_420_888`), and the requested vs. actually-bound resolution are
 * both recorded per attempt.
 */
internal data class AnalysisResolutionCandidate(
    val widthPx: Int,
    val heightPx: Int,
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "resolution must be strictly positive; was ${widthPx}x$heightPx" }
    }

    val aspectRatio: Double get() = widthPx.toDouble() / heightPx.toDouble()

    fun label(): String = "${widthPx}x$heightPx"
}

private const val NEAR_4_3_MIN_ASPECT = 1.28
private const val NEAR_4_3_MAX_ASPECT = 1.40
private const val NEAR_16_9_MIN_ASPECT = 1.70
private const val NEAR_16_9_MAX_ASPECT = 1.85
private const val PREFERRED_4_3_AREA = 640.0 * 480.0
private const val PREFERRED_16_9_AREA = 1280.0 * 720.0

/** Parses `"WxH"` strings (the exact format `CameraTopologyEntry.imageAnalysisStreamConfigurationsPx`
 * uses); silently skips malformed entries — an enumeration diagnostic must not throw on one odd row. */
internal fun parseAnalysisResolutions(sizes: List<String>): List<AnalysisResolutionCandidate> =
    sizes.mapNotNull { size ->
        val parts = size.split("x")
        if (parts.size != 2) return@mapNotNull null
        val width = parts[0].toIntOrNull() ?: return@mapNotNull null
        val height = parts[1].toIntOrNull() ?: return@mapNotNull null
        if (width <= 0 || height <= 0) return@mapNotNull null
        AnalysisResolutionCandidate(width, height)
    }

/**
 * Selects up to two deliberate, user-selectable candidates from the device-declared [supported]
 * sizes: the near-4:3 size closest (by area) to 640×480, and the near-16:9 size closest to 1280×720.
 * Deterministic (ties broken by smaller area, then smaller width); empty when the device declares
 * nothing in a band — never an invented size.
 */
internal fun selectAnalysisResolutionCandidates(
    supported: List<AnalysisResolutionCandidate>,
): List<AnalysisResolutionCandidate> {
    fun best(minAspect: Double, maxAspect: Double, preferredArea: Double): AnalysisResolutionCandidate? =
        supported
            .filter { it.aspectRatio in minAspect..maxAspect }
            .minWithOrNull(
                compareBy(
                    { abs(it.widthPx.toDouble() * it.heightPx.toDouble() - preferredArea) },
                    { it.widthPx.toDouble() * it.heightPx.toDouble() },
                    { it.widthPx },
                ),
            )

    val near43 = best(NEAR_4_3_MIN_ASPECT, NEAR_4_3_MAX_ASPECT, PREFERRED_4_3_AREA)
    val near169 = best(NEAR_16_9_MIN_ASPECT, NEAR_16_9_MAX_ASPECT, PREFERRED_16_9_AREA)
    return listOfNotNull(near43, near169).distinct()
}
