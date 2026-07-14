package dev.pointtosky.mobile.ar.camera.prediction

import dev.pointtosky.core.astro.catalog.StarRecord
import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection

/**
 * CAM-2b bounded diagnostic input adapter (task §2). Converts the phone's already-visibility-selected
 * [StarRecord] prefix — see `ArScreen.kt`'s `visibilitySelectedStars`, the exact same filter the legacy
 * renderer already applies — into CAM-2a's [EquatorialStarDirection], without making the CAM-2a
 * projector read the binary catalog, mutate it, sort inside it, or re-parse it a second way.
 *
 * ## What this does
 *  - Selects a **bounded, deterministic** subset: [stars] sorted brightest-first (ascending magnitude,
 *    stable Kotlin sort — ties keep their original catalog order), then the first [maxCount] entries.
 *    This is the "already-visible magnitude prefix" selection the task suggests: [stars] is expected to
 *    already be the phone's visibility-selected list (renderable + within the current magnitude limit),
 *    never the full ~42k-star catalog.
 *  - Converts each selected star's RA/Dec from degrees to radians **exactly once**, here.
 *  - Preserves a **stable catalog index**: [EquatorialStarDirection.catalogIndex] is
 *    [StarRecord.id]'s raw integer id — stable across catalog loads and independent of this function's
 *    own sort/bound, unlike a positional index that would shift if the selection changed.
 *  - Passes [StarRecord.magnitude] through as [EquatorialStarDirection.magnitude] when present.
 *
 * ## What this does not do
 *  - No spatial indexing — a plain sort-and-take, matching the task's explicit "no spatial indexing in
 *    CAM-2b" instruction.
 *  - No second catalog parser — [stars] is read from the exact same [StarRecord] list the legacy
 *    renderer's `PtskCatalogLoader`/`AstroCatalog` already produces.
 *  - No mutation of [stars] or any catalog object.
 *  - No sorting happens inside CAM-2a's `projectStars` — this function's sort is the only one, and it
 *    runs entirely before `projectStars` is ever called.
 *
 * [maxCount] defaults to [PREDICTED_STAR_OVERLAY_MAX_INPUT_STARS] — see that constant's KDoc for the
 * justification of 200 as the bound.
 */
fun selectPredictedStarDirections(
    stars: List<StarRecord>,
    maxCount: Int = PREDICTED_STAR_OVERLAY_MAX_INPUT_STARS,
): List<EquatorialStarDirection> {
    require(maxCount >= 0) { "maxCount must be non-negative; was $maxCount" }
    if (stars.isEmpty() || maxCount == 0) return emptyList()

    return stars
        .sortedBy { it.magnitude }
        .take(maxCount)
        .map { star ->
            EquatorialStarDirection.of(
                catalogIndex = star.id.raw,
                rightAscensionRad = Math.toRadians(star.rightAscensionDeg.toDouble()),
                declinationRad = Math.toRadians(star.declinationDeg.toDouble()),
                magnitude = star.magnitude.toDouble(),
            )
        }
}

/**
 * CAM-2b diagnostic subset bound (task §2's "suggested initial bound: maximum 100-300 stars"). `200`
 * sits in the middle of that range: the legacy renderer's own `StarPointLayer` already caps its
 * (production, non-diagnostic) Canvas point layer at `MAX_STAR_POINTS = 1500` (`ArScreen.kt`) without a
 * measured performance issue, so 200 markers plus a status panel is comfortably inside the existing
 * per-frame Canvas budget while staying small enough to visually distinguish individual predicted
 * markers from the legacy overlay during physical-device comparison (§10 of the task) — a denser marker
 * set would make that visual cross-check harder to read, not more useful.
 */
const val PREDICTED_STAR_OVERLAY_MAX_INPUT_STARS = 200
