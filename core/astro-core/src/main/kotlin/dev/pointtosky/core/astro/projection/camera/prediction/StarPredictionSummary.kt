package dev.pointtosky.core.astro.projection.camera.prediction

/**
 * Bounded, pure count summary of a [PredictedStarProjection] batch (CAM-2a §13) — for tests and a
 * future CAM-2b debug overlay. Carries only counters, never per-star text or logging.
 *
 * The four classification counts always sum to [inputCount]: [PredictedStarClassification] is
 * exhaustively covered by [summarizeStarPredictions], and the invariant is re-checked here too,
 * matching this codebase's convention of validating even a provably-true cross-field invariant.
 */
data class StarPredictionSummary(
    val inputCount: Int,
    val behindCameraCount: Int,
    val outsideImageCount: Int,
    val insideImageOutsideViewportCount: Int,
    val visibleInViewportCount: Int,
) {
    init {
        val sum = behindCameraCount + outsideImageCount + insideImageOutsideViewportCount + visibleInViewportCount
        require(sum == inputCount) {
            "classification counts ($sum) must sum to inputCount ($inputCount)"
        }
    }
}

/** Summarizes [projections] by [PredictedStarClassification], preserving no per-star detail. */
fun summarizeStarPredictions(projections: List<PredictedStarProjection>): StarPredictionSummary {
    var behindCamera = 0
    var outsideImage = 0
    var insideImageOutsideViewport = 0
    var visibleInViewport = 0

    for (projection in projections) {
        when (projection.classification) {
            PredictedStarClassification.BEHIND_CAMERA -> behindCamera++
            PredictedStarClassification.OUTSIDE_IMAGE -> outsideImage++
            PredictedStarClassification.INSIDE_IMAGE_OUTSIDE_VIEWPORT -> insideImageOutsideViewport++
            PredictedStarClassification.VISIBLE_IN_VIEWPORT -> visibleInViewport++
        }
    }

    return StarPredictionSummary(
        inputCount = projections.size,
        behindCameraCount = behindCamera,
        outsideImageCount = outsideImage,
        insideImageOutsideViewportCount = insideImageOutsideViewport,
        visibleInViewportCount = visibleInViewport,
    )
}
