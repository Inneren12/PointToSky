package dev.pointtosky.core.astro.projection.camera.prediction

/**
 * Bounded, pure count summary of a [PredictedStarProjection] batch (CAM-2a §13) — for tests and a
 * future CAM-2b debug overlay. Carries only counters, never per-star text or logging.
 *
 * Every count is non-negative, and the four classification counts always sum to [inputCount]:
 * [PredictedStarClassification] is exhaustively covered by [summarizeStarPredictions], and both
 * invariants are re-checked here too, matching this codebase's convention of validating even a
 * provably-true cross-field invariant. The sum is computed in `Long` specifically so that four
 * `Int`-range counts near [Int.MAX_VALUE] cannot silently wrap around into a value that happens to
 * equal [inputCount] by overflow coincidence — `Long` cannot overflow for any combination of four
 * `Int` values.
 */
data class StarPredictionSummary(
    val inputCount: Int,
    val behindCameraCount: Int,
    val outsideImageCount: Int,
    val insideImageOutsideViewportCount: Int,
    val visibleInViewportCount: Int,
) {
    init {
        require(inputCount >= 0) { "inputCount must be non-negative; was $inputCount" }
        require(behindCameraCount >= 0) { "behindCameraCount must be non-negative; was $behindCameraCount" }
        require(outsideImageCount >= 0) { "outsideImageCount must be non-negative; was $outsideImageCount" }
        require(insideImageOutsideViewportCount >= 0) {
            "insideImageOutsideViewportCount must be non-negative; was $insideImageOutsideViewportCount"
        }
        require(visibleInViewportCount >= 0) { "visibleInViewportCount must be non-negative; was $visibleInViewportCount" }

        val sum: Long =
            behindCameraCount.toLong() + outsideImageCount.toLong() + insideImageOutsideViewportCount.toLong() + visibleInViewportCount.toLong()
        require(sum == inputCount.toLong()) {
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
