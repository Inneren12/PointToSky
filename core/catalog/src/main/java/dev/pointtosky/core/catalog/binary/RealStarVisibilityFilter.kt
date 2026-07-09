package dev.pointtosky.core.catalog.binary

/**
 * Cheap "which records are visible" result: the mag-sorted prefix
 * `[0, count)` of a [PtskCat0Catalog], with no star data copied.
 */
data class VisibleStarSelection(val count: Int) {
    init {
        require(count >= 0) { "count must be >= 0, was $count" }
    }

    /** Record indices into the source catalog, e.g. `for (i in selection.indices) ...`. */
    val indices: IntRange get() = 0 until count

    val isEmpty: Boolean get() = count == 0
}

/**
 * Minimal magnitude-based visibility filter over [PtskCat0Catalog]. Since
 * catalog records are sorted ascending by magnitude, "visible up to a
 * limiting magnitude" is just the brighter-or-equal prefix
 * ([PtskCat0Catalog.countBrighterOrEqual]) — no copying, no GPS/SQM/Bortle
 * modeling, no camera or renderer wiring.
 */
object RealStarVisibilityFilter {

    /**
     * Selects the prefix of [catalog] visible at [limitingMagnitude].
     *
     * - A limit brighter than every record yields an empty selection.
     * - A limit at or above the catalog's faintest record yields all records.
     * - [Double.POSITIVE_INFINITY] selects every record; [Double.NEGATIVE_INFINITY]
     *   selects none. Both are treated as explicit "no limit" / "no stars" requests.
     *
     * @throws IllegalArgumentException if [limitingMagnitude] is `NaN`.
     */
    fun select(catalog: PtskCat0Catalog, limitingMagnitude: Double): VisibleStarSelection {
        require(!limitingMagnitude.isNaN()) { "limitingMagnitude must not be NaN" }

        val count = when (limitingMagnitude) {
            Double.POSITIVE_INFINITY -> catalog.count
            Double.NEGATIVE_INFINITY -> 0
            else -> catalog.countBrighterOrEqual(limitingMagnitude)
        }
        return VisibleStarSelection(count)
    }
}
