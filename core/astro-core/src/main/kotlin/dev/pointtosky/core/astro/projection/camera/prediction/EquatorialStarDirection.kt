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
 *    [dev.pointtosky.core.astro.coord.Equatorial]'s `raDeg`, just in radians), and is **always**
 *    stored canonically in `[0, 2π)`. The primary constructor is `private` and [of] — which wraps via
 *    [wrapRadTwoPi] before constructing — is the *only* way to obtain an instance:
 *    `@ConsistentCopyVisibility` makes the generated `copy()` private too (mirroring
 *    [dev.pointtosky.core.astro.projection.camera.CropScaleTransform]'s own convention), so a
 *    noncanonical RA cannot reach storage via direct construction *or* `copy()`. `init` re-derives
 *    and checks the same range as defense in depth, even though [of] is the only reachable path.
 *  - [declinationRad] is in radians, `[-π/2, +π/2]`, positive toward the north celestial pole
 *    (matching `Equatorial.decDeg`). Always range-checked — declination has no wraparound.
 *  - [magnitude] is the star's apparent visual magnitude (dimensionless; smaller = brighter), or
 *    `null` when unknown. Never used by the projector to sort, filter, or otherwise change which
 *    stars are projected or in what order (see `projectStars`).
 */
@ConsistentCopyVisibility
data class EquatorialStarDirection private constructor(
    val catalogIndex: Int,
    val rightAscensionRad: Double,
    val declinationRad: Double,
    val magnitude: Double? = null,
) {
    init {
        require(catalogIndex >= 0) { "catalogIndex must be non-negative; was $catalogIndex" }
        require(rightAscensionRad.isFinite()) { "rightAscensionRad must be finite; was $rightAscensionRad" }
        require(rightAscensionRad >= 0.0 && rightAscensionRad < 2.0 * PI) {
            "rightAscensionRad must be canonical [0, 2π); was $rightAscensionRad"
        }
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
         * The sole construction path: wraps [rightAscensionRad] into `[0, 2π)` (see [wrapRadTwoPi])
         * before constructing, so RA arithmetic upstream of the catalog (e.g. proper-motion or
         * epoch corrections) never has to pre-normalize its own output, and no noncanonical RA can
         * ever reach a stored [EquatorialStarDirection].
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
