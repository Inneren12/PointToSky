package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.resolutionselector.AspectRatioStrategy

/**
 * CAM-2c dual-basis experiment (P1 fix — preserve the requested aspect-ratio family explicitly).
 * Lives in `main` because [dev.pointtosky.mobile.ar.CameraPreview]'s optional
 * `analysisResolutionOverride` parameter carries it, but every declaration in this file is
 * `internal` — a main-source seam that exists only because [dev.pointtosky.mobile.ar.CameraPreview]
 * (itself `internal`, same reason) executes the CameraX bind, never public production API. No
 * production caller ever constructs one; only the `internalDebug`-only physical-camera experiment
 * does, via the same-module (not same-source-set) visibility `internal` grants across
 * `main`/`internalDebug` within one variant compilation.
 *
 * The family is assigned **once**, by the aspect band that selected the candidate
 * (`selectAnalysisResolutionCandidates`, `internalDebug`), and travels with the request from
 * selection through session state to the CameraX bind. It is never re-derived later from exact
 * integer ratios: a valid near-16:9 size like `848x480` (ratio ≈ 1.7667) fails a
 * `width * 9 == height * 16` check even though 16:9 is exactly the strategy it needs — inferring
 * the family from `WxH` equality is the defect this type exists to prevent.
 */
internal enum class AnalysisResolutionFamily {
    /** Selected from the near-4:3 band; always bound with
     * [AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY]. */
    NEAR_4_3,

    /** Selected from the near-16:9 band; always bound with
     * [AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY]. */
    NEAR_16_9,
}

/**
 * A complete `ImageAnalysis` resolution request: the exact requested dimensions plus the aspect
 * family of the band that selected them. The *actually bound* resolution is whatever each analyzed
 * frame reports — recorded separately by the experiment, never conflated with this request.
 */
internal data class AnalysisResolutionRequest(
    val widthPx: Int,
    val heightPx: Int,
    val family: AnalysisResolutionFamily,
) {
    init {
        require(widthPx > 0 && heightPx > 0) { "resolution must be strictly positive; was ${widthPx}x$heightPx" }
    }
}

/**
 * The one family → [AspectRatioStrategy] decision (pure, no CameraX binding required — the
 * predefined strategy singletons are plain objects, so this is JVM-unit-testable): [AnalysisResolutionFamily.NEAR_16_9]
 * always maps to [AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY] and
 * [AnalysisResolutionFamily.NEAR_4_3] always to
 * [AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY] — regardless of whether the requested
 * `WxH` is an exact 16:9/4:3 ratio. `internal` (not `private`) so `AnalysisResolutionCandidatesTest`
 * (`testInternalDebug`) can exercise this pure mapping directly, without going through
 * [dev.pointtosky.mobile.ar.CameraPreview]'s CameraX bind — it must never become part of the public
 * production API, and never expose [AspectRatioStrategy] through one.
 */
internal fun aspectRatioStrategyFor(family: AnalysisResolutionFamily): AspectRatioStrategy =
    when (family) {
        AnalysisResolutionFamily.NEAR_4_3 -> AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
        AnalysisResolutionFamily.NEAR_16_9 -> AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
    }
