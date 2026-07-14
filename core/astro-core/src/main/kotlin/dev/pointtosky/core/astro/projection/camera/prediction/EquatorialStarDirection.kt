package dev.pointtosky.core.astro.projection.camera.prediction

import kotlin.math.PI

/**
 * One catalog star's equatorial direction as pure input to the CAM-2a prediction pipeline.
 *
 * Deliberately decoupled from the binary catalog reader and any UI star model: this carries only
 * what the projector needs to compute a predicted direction, plus [catalogIndex] so a caller can map
 * a result back to its own catalog entry. It never carries a catalog name, string, texture, or any
 * renderer state.
 *
 * ## Conventions
 *  - [rightAscensionRad] is in radians, increasing eastward along the celestial equator (matching
 *    [dev.pointtosky.core.astro.coord.Equatorial]'s `raDeg`, just in radians). The primary
 *    constructor only requires it to be finite — it does **not** require the canonical `[0, 2π)`
 *    range, so a directly-constructed (or `copy()`-derived) value may legitimately sit outside that
 *    range. Prefer [EquatorialStarDirection.of], which wraps into `[0, 2π)` via [wrapRadTwoPi] before
 *    constructing — this is the "normalize in the factory" policy the CAM-2a spec calls for, chosen
 *    (over rejecting out-of-range input) because upstream RA arithmetic can easily drift a hair
 *    outside `[0, 2π)` and that is not a caller error worth rejecting.
 *  - [declinationRad] is in radians, `[-π/2, +π/2]`, positive toward the north celestial pole
 *    (matching `Equatorial.decDeg`). Always range-checked — declination has no wraparound.
 *  - [magnitude] is the star's apparent visual magnitude (dimensionless; smaller = brighter), or
 *    `null` when unknown. Never used by the projector to sort, filter, or otherwise change which
 *    stars are projected or in what order (see `projectStars`).
 */
data class EquatorialStarDirection(
    val catalogIndex: Int,
    val rightAscensionRad: Double,
    val declinationRad: Double,
    val magnitude: Double? = null,
) {
    init {
        require(catalogIndex >= 0) { "catalogIndex must be non-negative; was $catalogIndex" }
        require(rightAscensionRad.isFinite()) { "rightAscensionRad must be finite; was $rightAscensionRad" }
        require(declinationRad.isFinite()) { "declinationRad must be finite; was $declinationRad" }
        require(declinationRad in -PI / 2.0..PI / 2.0) {
            "declinationRad must be in [-π/2, π/2]; was $declinationRad"
        }
        require(magnitude == null || magnitude.isFinite()) {
            "magnitude must be finite when present; was $magnitude"
        }
    }

    companion object {
        /**
         * Canonical factory: wraps [rightAscensionRad] into `[0, 2π)` (see [wrapRadTwoPi]) before
         * constructing, so RA arithmetic upstream of the catalog (e.g. proper-motion or epoch
         * corrections) never has to pre-normalize its own output.
         */
        fun of(
            catalogIndex: Int,
            rightAscensionRad: Double,
            declinationRad: Double,
            magnitude: Double? = null,
        ): EquatorialStarDirection {
            require(rightAscensionRad.isFinite()) { "rightAscensionRad must be finite; was $rightAscensionRad" }
            return EquatorialStarDirection(
                catalogIndex = catalogIndex,
                rightAscensionRad = wrapRadTwoPi(rightAscensionRad),
                declinationRad = declinationRad,
                magnitude = magnitude,
            )
        }
    }
}
