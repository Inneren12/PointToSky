package dev.pointtosky.core.astro.projection.camera.match

import dev.pointtosky.core.astro.projection.camera.detect.DetectedSource
import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarProjection

/**
 * Everything a star matcher is allowed to read for one frame, and nothing else: the frame's detected
 * point sources, the catalog candidates that could explain them, the angular scale relating the two,
 * and — optionally, and hedged about below — where a prior pointing estimate expected those candidates
 * to land.
 *
 * **This is the contract, not the matcher.** No association, no geometric invariant, no hash, no
 * RANSAC, no pose. Assembling this type is the last step before a matcher exists; nothing here decides
 * which detection is which star.
 *
 * ## Pixels to angles
 * A geometric matcher works in angles, and the one sanctioned route from a detected centroid to one is
 * [AnalysisBufferScale.cameraRayFor]:
 * ```text
 * DetectedSource.xPx/yPx  ->  scale.cameraRayFor(...)  ->  unit camera ray  ->  angular invariant
 * ```
 * It delegates to the single canonical inverse of the production projection, which is what applies the
 * non-central principal point, `fx != fy`, and the axis-swap/negation flags correctly. A matcher must
 * not write its own inverse, and must not obtain an invariant by multiplying a pixel distance by
 * [AnalysisBufferScale.radiansPerPixelXOnAxis] — that is an on-axis first-order approximation for
 * sizing tolerances, and it is visibly wrong by the frame edge.
 *
 * ## One pixel space, so a residual is a subtraction
 * [detections]' centroids, [priorProjections]' `imagePoint`, and every pixel quantity on [scale] are all
 * full analysis-buffer pixels in the project's continuous edge-coordinate convention (raster sample
 * `[x, y]` centred at `(x + 0.5, y + 0.5)`; canonical statement in `PixelGeometry.kt`'s file KDoc and
 * `docs/camera_coordinate_calibration_contract.md` §9.2). There is no transform between them, and this
 * type introduces none: `detection.xPx - prediction.imagePoint.x` is the residual, full stop. That
 * equality is pinned against the real projection by `PixelConventionBridgeTest`, and again for this DTO
 * by `StarMatcherInputTest`.
 *
 * ## What [detections] does **not** carry, and what a matcher must therefore do for itself
 *  - **[DetectedSource.brightness] is not a magnitude and must never be converted to one.** It is flux
 *    above the local background summed over the pixels that cleared the threshold, in raw 8-bit luma
 *    units, with no zero point, no exposure normalisation, no colour term and no aperture correction —
 *    and it is truncated by the threshold by an amount that depends on how broad the profile is, so two
 *    sources of *equal total flux* and different widths do not measure equal (`StarDetector.kt`'s file
 *    KDoc; demonstrated in `MatcherInputBrightnessContractTest`). It orders sources within one frame
 *    and nothing more. The only magnitudes in this input are [EquatorialStarDirection.magnitude] on
 *    [candidates], which come from the catalog. A matcher may use [DetectedSource.brightness] as a
 *    within-frame *rank*; it may not build a photometric term out of it, compare it across frames, or
 *    feed it anywhere a magnitude is expected.
 *  - **No centroid uncertainty.** The detector reports no σ, no FWHM, and no covariance, so this input
 *    cannot supply one. A matcher that needs a positional error model derives its own — from
 *    [DetectedSource.pixelCount], [DetectedSource.peakLuma] and
 *    [DetectedSource.localBackgroundLuma], which are carried for exactly that purpose — and states its
 *    assumptions where it does so. It must not read the absence of an uncertainty as "exact".
 *  - **[DetectedSource.saturated] and [DetectedSource.nearEdge] are positional caveats, not rejects.**
 *    A saturated source has a clipped, flat-topped profile (its centroid is usable, its brightness is a
 *    lower bound); a near-edge source has a truncated PSF and a centroid biased inward. The detector
 *    keeps and flags them on purpose so a matcher can widen a tolerance rather than never learn they
 *    were there.
 *
 * ## Detection identity is list position
 * The detector has no stable per-source id, and this PR does not add one: `detectStars` returns a list
 * ordered brightest-first with ties broken by `yPx` then `xPx` — a total order that depends only on the
 * pixels — and inserting an id field into [DetectedSource] would put a value into that ordering's own
 * data class for the sake of logging. So the identity of a detection **is its index in [detections]**,
 * which is deterministic for identical input precisely because that ordering is. Two consequences a
 * caller must respect: the index is only meaningful together with the frame it came from, and
 * [detections] must be handed to [of] exactly as `detectStars` returned it — re-sorting or filtering it
 * *before* construction renumbers every detection. After construction it cannot be renumbered at all,
 * because [of] snapshots the list. A stable, frame-independent detection id is future work and belongs
 * with whatever logs matched pairs, not with the detector's ordering contract.
 *
 * ## The lists are snapshotted, so the invariants hold for the object's lifetime
 * A Kotlin `List` is read-only, not immutable — a caller can hand over an `ArrayList` and keep mutating
 * it. [of] therefore copies all three lists on the way in, so what `init` validates is exactly what a
 * consumer later reads, and no subsequent mutation by the caller can renumber a detection, defeat the
 * candidate-uniqueness check, or leave a prior describing a star that is no longer a candidate. The
 * elements are shared rather than copied — they are immutable value types, so only the containers need
 * snapshotting. See [of] for the full reasoning.
 *
 * ## [priorProjections] is a hint, never an answer
 * `projectStars`' output for [candidates], when a pointing estimate exists. It is here because a
 * matcher may legitimately use a prior to *bound* its search — a smaller cone, a coarse rejection of
 * candidates behind the camera — and because a diagnostic that plots residuals needs it.
 *
 * It must never be the basis of the association itself. `DetectionEvaluation.kt`'s file KDoc states the
 * reason for the metrics utility and it holds here: pairing a detection to its nearest prediction and
 * then fitting a pose to that pairing can only return the pose the predictions came from. The prior
 * carries exactly the pointing error the matcher exists to correct, so a matcher that leans on it
 * cannot detect its own failure. Empty is the honest default, and a correct matcher must work with it
 * empty.
 *
 * @property detections the frame's point sources, in exactly the order `detectStars` returned them; a
 *   snapshot of what was handed to [of], never the caller's own list.
 * @property candidates catalog stars that could plausibly appear in this frame — typically a
 *   [StarCatalogQuery] cone around the current pointing estimate, sized from
 *   [AnalysisBufferScale.enclosingConeRadiusRad] (a cone takes a radius; half of
 *   [AnalysisBufferScale.horizontalFieldOfViewRad] would clip the frame's corners, and would not even
 *   be half the worst-case span once the principal point is off centre). Order carries no meaning;
 *   identity is [EquatorialStarDirection.catalogIndex], which is unique across the list.
 * @property scale the frame's pixel↔ray geometry — [AnalysisBufferScale.cameraRayFor] for the exact
 *   camera ray of any detected centroid, the per-edge angular extents, and
 *   [AnalysisBufferScale.quality] for whether these numbers are a real per-device measurement or the
 *   legacy fixed-FOV fallback.
 * @property priorProjections optional predicted positions for [candidates]; see the section above for
 *   the one thing it may not be used for. Every entry's `catalogIndex` must name a star in
 *   [candidates] — a prediction about a star the matcher was never given is a mis-assembled input, not
 *   an extra hint.
 */
@ConsistentCopyVisibility
data class StarMatcherInput private constructor(
    val detections: List<DetectedSource>,
    val candidates: List<EquatorialStarDirection>,
    val scale: AnalysisBufferScale,
    val priorProjections: List<PredictedStarProjection>,
) {
    init {
        val candidateIndices = HashSet<Int>(candidates.size)
        for (candidate in candidates) {
            require(candidateIndices.add(candidate.catalogIndex)) {
                "candidates must carry distinct catalogIndex values; ${candidate.catalogIndex} appears twice"
            }
        }
        val predictedIndices = HashSet<Int>(priorProjections.size)
        for (projection in priorProjections) {
            require(predictedIndices.add(projection.catalogIndex)) {
                "priorProjections must carry distinct catalogIndex values; ${projection.catalogIndex} appears twice"
            }
            require(projection.catalogIndex in candidateIndices) {
                "priorProjections must only describe stars present in candidates; ${projection.catalogIndex} is not one"
            }
        }
    }

    /** How many point sources this frame yielded; valid detection ids are `0 until detectionCount`. */
    val detectionCount: Int get() = detections.size

    /** How many catalog stars the matcher may choose from. */
    val candidateCount: Int get() = candidates.size

    /**
     * The detection identified by [detectionId] — i.e. `detections[detectionId]`, spelled out so a call
     * site reads as an identity lookup and so the "identity is list position" rule above has one place
     * to point at.
     *
     * @throws IndexOutOfBoundsException if [detectionId] is not in `0 until detectionCount`.
     */
    fun detectionAt(detectionId: Int): DetectedSource = detections[detectionId]

    companion object {
        /**
         * The sole construction path. Snapshots all three lists on the way in, so what `init` validates
         * and what a consumer later reads are the same thing.
         *
         * ## Why a copy is not paranoia here
         * A Kotlin `List` is read-only, not immutable: a caller can hand over an `ArrayList` and keep
         * mutating it. Without a snapshot, every invariant this type establishes could be undone after
         * construction, silently and from a distance:
         *  - reordering or clearing [detections] **renumbers every detection identity**, and identity
         *    here *is* the list index (see the class KDoc), so a matched pair logged before the mutation
         *    would name a different source after it;
         *  - inserting a repeated `catalogIndex` into [candidates] defeats the uniqueness `init` just
         *    checked, making a matched pair ambiguous;
         *  - removing a candidate leaves [priorProjections] describing a star the matcher was never
         *    given — precisely the state `init` rejects at construction.
         *
         * [toList] is what stores the copies: it always returns a fresh container (or an immutable
         * singleton/empty instance), never a view onto the argument, so no stored list shares backing
         * storage with anything the caller still holds.
         *
         * The elements themselves are **not** copied, and must not be: [DetectedSource],
         * [EquatorialStarDirection] and [PredictedStarProjection] are immutable value types, so copying
         * them would change nothing except identity. Only the containers are snapshotted, and the order
         * of [detections] is preserved exactly as supplied — nothing here sorts or filters, because
         * re-ordering the detector's output is the very thing that would break identity.
         *
         * The primary constructor is `private` and `@ConsistentCopyVisibility` makes the generated
         * `copy()` private too, so there is no path — direct construction or `copy()` — that can install
         * a caller-owned list. This mirrors [EquatorialStarDirection]'s own construction contract.
         *
         * @throws IllegalArgumentException if [candidates] or [priorProjections] repeat a
         *   `catalogIndex`, or if a [priorProjections] entry names a star absent from [candidates].
         */
        fun of(
            detections: List<DetectedSource>,
            candidates: List<EquatorialStarDirection>,
            scale: AnalysisBufferScale,
            priorProjections: List<PredictedStarProjection> = emptyList(),
        ): StarMatcherInput =
            StarMatcherInput(
                detections = detections.toList(),
                candidates = candidates.toList(),
                scale = scale,
                priorProjections = priorProjections.toList(),
            )
    }
}
