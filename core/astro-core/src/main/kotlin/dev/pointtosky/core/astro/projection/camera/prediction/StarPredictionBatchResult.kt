package dev.pointtosky.core.astro.projection.camera.prediction

/** Why `projectStars` returned [StarPredictionBatchResult.IntrinsicsMappingUnavailable]. */
enum class IntrinsicsMappingUnavailableReason {
    /**
     * [geometry].intrinsics.intrinsics.reference is
     * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference.PhysicalSensor] — the
     * resolved FOV is measured over the physical sensor (or another non-buffer region) with no
     * recorded crop/scale mapping to any analysis buffer, so it cannot be used to build a
     * buffer-space [PinholeProjectionModel] without fabricating that mapping. See
     * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference]'s KDoc.
     */
    PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED,

    /**
     * [geometry].intrinsics.intrinsics.reference is
     * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference.Unspecified] — e.g. a
     * legacy fallback resolved before the first analyzed frame's real dimensions were known, or
     * constructed dimensionlessly outside CAM-1f's coordinator. There are no reference dimensions to
     * check against [geometry].frame's buffer at all, so this can never be treated as
     * analysis-buffer-compatible.
     */
    ANALYSIS_BUFFER_REFERENCE_MISSING,

    /**
     * [geometry].intrinsics.intrinsics.reference is an
     * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference.AnalysisBuffer], but its
     * `widthPx`/`heightPx` do **not** exactly match [geometry].frame's buffer dimensions — e.g. the
     * intrinsics were resolved for one buffer/session and then reused against a different one, or a
     * test fixture supplied mismatched dimensions. Matching aspect ratio is not sufficient (see
     * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference.AnalysisBuffer]'s KDoc):
     * only an *exact* width/height match is accepted, so a stale or wrong-buffer reference can never
     * be silently reused.
     */
    ANALYSIS_BUFFER_DIMENSIONS_MISMATCH,
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
    /**
     * [projections] preserves input order; see `projectStars`'s own contract.
     *
     * The primary constructor is `private` (`@ConsistentCopyVisibility`, matching this codebase's
     * `EquatorialStarDirection`/`StarProjectionContext` convention): the sole public construction path
     * is [of], which stores a **defensive copy** ([List.toList]) of the list it is given. Without
     * this, a caller holding the original (possibly mutable) list passed to [of] could mutate it after
     * construction and silently change an already-returned, supposedly-immutable [Ready] result out
     * from under whoever received it — this bounded batch result is documented as immutable and must
     * actually be so, not just immutable by caller convention.
     */
    @ConsistentCopyVisibility
    data class Ready private constructor(
        val projections: List<PredictedStarProjection>,
    ) : StarPredictionBatchResult {
        companion object {
            /** The only public way to build a [Ready]; see the class KDoc for why. */
            fun of(projections: List<PredictedStarProjection>): Ready = Ready(projections.toList())
        }
    }

    /** No prediction was computed for any star in the batch; see [reason]. */
    data class IntrinsicsMappingUnavailable(
        val reason: IntrinsicsMappingUnavailableReason,
    ) : StarPredictionBatchResult
}
