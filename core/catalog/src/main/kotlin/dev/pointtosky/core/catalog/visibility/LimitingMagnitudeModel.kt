package dev.pointtosky.core.catalog.visibility

import dev.pointtosky.core.astro.visibility.darkNelmFromSqm

/**
 * Pure, deterministic sky-quality -> limiting-magnitude model (VF-1c).
 *
 * Converts manual, user-supplied sky-quality input into a `limitingMagnitude`
 * that [dev.pointtosky.core.catalog.binary.RealStarVisibilityFilter.select] can
 * consume. Intentionally conservative and minimal: no GPS, no automatic Bortle
 * lookup from a map/grid, no Moon phase/altitude, no twilight/Sun altitude, no
 * weather/clouds, no camera matching, no renderer/UI wiring.
 */
object LimitingMagnitudeModel {

    /**
     * Output range this model clamps to. Matches the packed real-star catalog's
     * magnitude limit ([dev.pointtosky.core.catalog.binary.PtskCat0Catalog.magLimit]).
     */
    val SUPPORTED_RANGE: ClosedFloatingPointRange<Double> = 0.0..8.0

    private val BORTLE_LIMITING_MAGNITUDE: Map<Int, Double> = mapOf(
        1 to 7.6,
        2 to 7.1,
        3 to 6.6,
        4 to 6.1,
        5 to 5.6,
        6 to 5.1,
        7 to 4.6,
        8 to 4.1,
        9 to 3.6,
    )

    /**
     * Naked-eye limiting magnitude for [bortleClass] (1 = darkest, 9 = brightest
     * inner-city sky), clamped to [SUPPORTED_RANGE].
     *
     * @throws IllegalArgumentException if [bortleClass] is not in `1..9`.
     */
    fun fromBortle(bortleClass: Int): Double {
        val mag = BORTLE_LIMITING_MAGNITUDE[bortleClass]
            ?: throw IllegalArgumentException("Bortle class must be 1..9, was $bortleClass")
        return mag.coerceIn(SUPPORTED_RANGE)
    }

    /**
     * Naked-eye limiting magnitude from a direct sky-quality-meter reading
     * ([sqm], mag/arcsec^2). Delegates to the repository's canonical SQM
     * calibration, [darkNelmFromSqm] (see `core/astro/.../SkyBrightness.kt`),
     * so this model doesn't drift from the light-pollution-grid calibration
     * used elsewhere. That function already extrapolates and clamps
     * out-of-range SQM to the Bortle 1..9 equivalent, so no separate SQM
     * range clamp is applied here — if the calibration anchors change,
     * update `SkyBrightness.kt`, and this delegation picks it up automatically.
     *
     * @throws IllegalArgumentException if [sqm] is `NaN` or infinite.
     */
    fun fromSqm(sqm: Double): Double {
        require(sqm.isFinite()) { "sqm must be a finite number, was $sqm" }

        return darkNelmFromSqm(sqm).coerceIn(SUPPORTED_RANGE)
    }
}
