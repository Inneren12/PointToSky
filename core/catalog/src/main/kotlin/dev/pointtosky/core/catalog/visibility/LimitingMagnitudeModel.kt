package dev.pointtosky.core.catalog.visibility

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

    /** Plausible sky-brightness meter (SQM) input range, in mag/arcsec^2. */
    val SQM_RANGE: ClosedFloatingPointRange<Double> = 16.0..22.0

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
     * ([sqm], mag/arcsec^2). [sqm] is clamped to [SQM_RANGE] first, then mapped
     * linearly onto the same endpoints as [fromBortle]'s Bortle 9 and Bortle 1
     * values, so a darker (higher) SQM reading yields a fainter (higher)
     * limiting magnitude. This is a simple monotonic approximation, not a
     * photometrically precise conversion.
     *
     * @throws IllegalArgumentException if [sqm] is `NaN` or infinite.
     */
    fun fromSqm(sqm: Double): Double {
        require(sqm.isFinite()) { "sqm must be a finite number, was $sqm" }

        val clampedSqm = sqm.coerceIn(SQM_RANGE)
        val brightestMag = BORTLE_LIMITING_MAGNITUDE.getValue(9)
        val darkestMag = BORTLE_LIMITING_MAGNITUDE.getValue(1)
        val t = (clampedSqm - SQM_RANGE.start) / (SQM_RANGE.endInclusive - SQM_RANGE.start)
        val mag = brightestMag + t * (darkestMag - brightestMag)
        return mag.coerceIn(SUPPORTED_RANGE)
    }
}
