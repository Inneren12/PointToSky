package dev.pointtosky.core.astro.projection.camera.prediction

/** Why `projectStars` returned [StarPredictionBatchResult.IntrinsicsMappingUnavailable]. */
enum class IntrinsicsMappingUnavailableReason {
    /**
     * [geometry].intrinsics.intrinsics.referenceSpace is
     * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReferenceSpace.PHYSICAL_SENSOR] —
     * the resolved FOV is measured over the physical sensor (or another non-buffer region) with no
     * recorded crop/scale mapping to the analyzed buffer's own pixel grid, so it cannot be used to
     * build a buffer-space [PinholeProjectionModel] without fabricating that mapping. See
     * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReferenceSpace]'s KDoc.
     */
    PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED,
}

/**
 * Whole-batch outcome of `projectStars` (CAM-2a hardening). Split out from a bare
 * `List<PredictedStarProjection>` because the intrinsics reference-space check
 * ([IntrinsicsMappingUnavailableReason]) is a **call-level** fact — true or false for the entire
 * [geometry] the batch was requested against — not a per-star classification; folding it into
 * [PredictedStarClassification] would misrepresent an intrinsics-contract gap as if it were a
 * per-star geometry outcome (e.g. `OUTSIDE_IMAGE`), which it is not.
 *
 * Never both variants at once, and never a bare `null`/empty list standing in for "unavailable" —
 * every caller must handle *why* a batch could not be produced, matching this codebase's existing
 * `CameraSessionGeometryResult`/`FrameRotationPairingResult` convention of categorized results
 * instead of nullable/absent values.
 */
sealed interface StarPredictionBatchResult {
    /** [projections] preserves input order; see `projectStars`'s own contract. */
    data class Ready(
        val projections: List<PredictedStarProjection>,
    ) : StarPredictionBatchResult

    /** No prediction was computed for any star in the batch; see [reason]. */
    data class IntrinsicsMappingUnavailable(
        val reason: IntrinsicsMappingUnavailableReason,
    ) : StarPredictionBatchResult
}
