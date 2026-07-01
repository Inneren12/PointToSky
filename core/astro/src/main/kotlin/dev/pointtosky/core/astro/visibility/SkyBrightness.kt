package dev.pointtosky.core.astro.visibility

/**
 * Continuous sky-brightness reading in Sky Quality Meter magnitudes per square arcsecond (SQM).
 * Canonical value produced by the light-pollution grid; [Bortle] remains the discrete manual scale.
 */
@JvmInline
value class SkyBrightness(val sqmMag: Double) {
    /** Fractional Bortle (1.0–9.0), monotone decreasing in SQM. Display-only; not precise. */
    val fractionalBortle: Double get() = bortleFromSqm(sqmMag)

    /** Continuous dark-sky NELM; equals the legacy per-class value at integer Bortle. */
    val darkNelm: Double get() = darkNelmFromSqm(sqmMag)
}

/** `(sqm, fractionalBortle)` anchors, monotonically decreasing in sqm; class-boundary midpoints. */
private val BORTLE_ANCHORS = listOf(
    21.75 to 1.5,
    21.50 to 2.5,
    21.25 to 3.5,
    20.40 to 4.5,
    19.10 to 5.5,
    18.50 to 6.5,
    18.00 to 7.5,
    17.50 to 8.5,
)

/** `(bortle, darkNelm)` anchors — exactly [Bortle.darkNelm] per class, descending in bortle. */
private val NELM_ANCHORS = listOf(
    9.0 to 4.0,
    8.0 to 4.3,
    7.0 to 4.8,
    6.0 to 5.3,
    5.0 to 5.8,
    4.0 to 6.3,
    3.0 to 6.8,
    2.0 to 7.3,
    1.0 to 7.8,
)

/**
 * Piecewise-linear interpolation over anchors sorted by descending `x`. Outside the anchor range,
 * extrapolates along the nearest segment's slope, then clamps to [yRange].
 */
private fun linInterp(
    x: Double,
    anchors: List<Pair<Double, Double>>,
    yRange: ClosedFloatingPointRange<Double>,
): Double {
    val lastIndex = anchors.size - 1
    val segment = when {
        x >= anchors.first().first -> 0
        x <= anchors.last().first -> lastIndex - 1
        else -> (0 until lastIndex).first { i -> x <= anchors[i].first && x >= anchors[i + 1].first }
    }
    val (x0, y0) = anchors[segment]
    val (x1, y1) = anchors[segment + 1]
    val t = (x - x0) / (x1 - x0)
    return (y0 + t * (y1 - y0)).coerceIn(yRange)
}

/** SQM → fractional Bortle, clamped to [1.0, 9.0]. */
fun bortleFromSqm(sqm: Double): Double = linInterp(sqm, BORTLE_ANCHORS, 1.0..9.0)

/** SQM → continuous dark-sky NELM, clamped to [4.0, 7.8]; exact at integer Bortle values. */
fun darkNelmFromSqm(sqm: Double): Double {
    val bortle = bortleFromSqm(sqm)
    return linInterp(bortle, NELM_ANCHORS, 4.0..7.8)
}
