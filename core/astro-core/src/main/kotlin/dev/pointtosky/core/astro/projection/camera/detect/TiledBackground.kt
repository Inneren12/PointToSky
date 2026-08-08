package dev.pointtosky.core.astro.projection.camera.detect

import kotlin.math.roundToInt

/**
 * SKY-2: a per-tile, bilinearly interpolated estimate of the sky background and its noise level.
 *
 * ## Why not one number for the whole frame
 * A night-sky frame taken from anywhere a person actually stands has a light-pollution gradient across
 * it — bright near the horizon, dark near the zenith, often several tens of luma levels end to end. A
 * single global `mean + k*sigma` threshold cannot separate "star" from "sky" on such a frame: set it
 * high enough to reject the bright end and every star over the dark end is lost; set it low enough to
 * keep those and the bright end returns one enormous blob. The gradient is not noise to be averaged
 * away, it is signal about where the threshold should sit, so it is measured per region.
 *
 * ## Why the median and the lower quartile
 * Stars are exactly the pixels this estimate must not include, and they contaminate only the *upper*
 * tail of a tile's intensity distribution. So the level is the tile median (already highly robust to a
 * few bright outliers), and the spread is taken from the **lower** quartile alone:
 * `sigma = (median - q25) / 0.6745`, the standard-normal relation between the interquartile half-width
 * and sigma. Reading the spread from below the median means a tile full of stars still reports the
 * noise of its *sky*, where a plain standard deviation would report the stars and push the threshold
 * above them. Both statistics come from a histogram on the 1-luma grid the data already lives on, which
 * is exact for 8-bit values and needs no sort.
 *
 * ## Why the spread is measured on the residual, not on the raw pixels
 * A gradient steep enough to ramp across a single tile is *structure*, not noise, but a spread measured
 * over that tile's raw values cannot tell the two apart and reports the ramp as though it were noise —
 * several times the true sigma on a strong light-pollution gradient, pushing the threshold up with it and
 * losing the faintest stars exactly where the sky is worst. So the level grid is built first, and the
 * spread is then measured over `pixel - levelAt(pixel centre)`: the in-tile ramp is removed by the same
 * interpolated model the detector thresholds against, and what is left is the per-pixel noise. Between
 * the outermost tile centres a bilinear interpolation is exact on a linear ramp, so the subtraction there
 * removes the gradient completely; outside them the model clamps flat by design (see below), and the
 * residual in those outer half-tiles still carries whatever the sky ramps over that margin. That
 * remainder is the old, conservative behaviour confined to the frame's border rather than applied to
 * every tile.
 *
 * ## Why bilinear interpolation
 * Per-tile constant values produce a visible step at every tile boundary. Over a gradient, a step is
 * indistinguishable from a run of above-threshold pixels along the seam, so a blocky background model
 * manufactures long thin false sources on exactly the frames it was introduced to handle. Interpolating
 * between tile centres removes the seams; outside the centre grid the edge tiles are clamped, which
 * extrapolates flat rather than inventing a trend from one tile.
 *
 * The tile must be much larger than a star for any of this to hold: at [StarDetectorDefaults.BACKGROUND_TILE_SIZE_PX]
 * a star occupies well under a percent of a tile's pixels and cannot move its median. A tile sized down
 * near the PSF would absorb the star into the background and the detector would find nothing.
 */
class TiledBackground internal constructor(
    /**
     * The tile edge that was *requested*. Nominal only: the last tile on each axis absorbs the
     * remainder and is therefore wider or taller than this whenever the frame is not an exact multiple
     * of it. Nothing in the interpolation reads this value — see [centresXPx].
     */
    val nominalTileSizePx: Int,
    val tilesX: Int,
    val tilesY: Int,
    /**
     * The **actual** centre coordinate of each tile column, in continuous buffer-pixel coordinates
     * (`docs/camera_coordinate_calibration_contract.md` §9.2), derived from that tile's real
     * `[start, end)` bounds rather than from [nominalTileSizePx].
     *
     * This is the whole point of storing them: with a remainder tile, `(index + 0.5) * tileSizePx` is
     * *not* where the tile is. For a 100 px wide frame at a nominal 64 px tile, column 1 spans
     * `[64, 100)` and is centred at 82, where the nominal formula says 96 — so an interpolation that
     * trusted the formula would place the last measured value 14 px to the right of the pixels it was
     * actually measured over, shifting the whole model near the frame's right edge.
     */
    private val centresXPx: DoubleArray,
    /** The actual centre coordinate of each tile row; see [centresXPx]. */
    private val centresYPx: DoubleArray,
    private val levels: DoubleArray,
    private val sigmas: DoubleArray,
) {
    /** The estimated background level at ([x], [y]), in luma units. */
    fun levelAt(
        x: Int,
        y: Int,
    ): Double = interpolate(levels, x, y)

    /** The estimated background noise sigma at ([x], [y]), in luma units. Never negative. */
    fun sigmaAt(
        x: Int,
        y: Int,
    ): Double = interpolate(sigmas, x, y)

    /** The tile-grid value at ([tileX], [tileY]) before interpolation — exposed for diagnostics and tests. */
    fun tileLevel(
        tileX: Int,
        tileY: Int,
    ): Double = levels[tileY * tilesX + tileX]

    /** The tile-grid sigma at ([tileX], [tileY]) before interpolation — exposed for diagnostics and tests. */
    fun tileSigma(
        tileX: Int,
        tileY: Int,
    ): Double = sigmas[tileY * tilesX + tileX]

    /** The actual centre of tile column [tileX], in continuous buffer-pixel coordinates. */
    fun tileCentreXPx(tileX: Int): Double = centresXPx[tileX]

    /** The actual centre of tile row [tileY], in continuous buffer-pixel coordinates. */
    fun tileCentreYPx(tileY: Int): Double = centresYPx[tileY]

    /**
     * Bilinear interpolation over the measured tile centres.
     *
     * The pixel is placed at its own continuous centre, `x + 0.5`, on the same axis the tile centres
     * live on, so a pixel sitting exactly on a tile centre lands on that node with zero fractional
     * weight regardless of how wide that tile happens to be. Each axis contributes an independent
     * weight and the result is their product; outside the first or last measured centre the weight
     * collapses to zero and the nearest tile's value is returned unchanged, so the model extrapolates
     * flat instead of continuing a trend past its last measurement.
     *
     * No tile is special-cased here. The remainder tile is handled purely by [centresXPx]/[centresYPx]
     * holding where it really is.
     */
    private fun interpolate(
        grid: DoubleArray,
        x: Int,
        y: Int,
    ): Double {
        val px = x + PIXEL_CENTRE_OFFSET
        val py = y + PIXEL_CENTRE_OFFSET
        val x0 = centresXPx.lowerNodeIndex(px)
        val y0 = centresYPx.lowerNodeIndex(py)
        val x1 = (x0 + 1).coerceAtMost(tilesX - 1)
        val y1 = (y0 + 1).coerceAtMost(tilesY - 1)
        val fx = centresXPx.fractionBetween(x0, x1, px)
        val fy = centresYPx.fractionBetween(y0, y1, py)

        val top = grid[y0 * tilesX + x0] * (1.0 - fx) + grid[y0 * tilesX + x1] * fx
        val bottom = grid[y1 * tilesX + x0] * (1.0 - fx) + grid[y1 * tilesX + x1] * fx
        return top * (1.0 - fy) + bottom * fy
    }
}

/**
 * The largest node index whose centre is at or below [coordinate], clamped into the array.
 *
 * Binary search rather than arithmetic, because the centres are only *almost* evenly spaced — the
 * remainder tile breaks any closed-form index formula, and rediscovering the tile bounds here is
 * exactly what storing the real centres exists to avoid. A coordinate below the first centre returns
 * node 0, which [fractionBetween] then turns into a flat clamp.
 */
private fun DoubleArray.lowerNodeIndex(coordinate: Double): Int {
    if (size == 1 || coordinate <= this[0]) return 0
    if (coordinate >= this[size - 1]) return size - 1
    var low = 0
    var high = size - 1
    while (high - low > 1) {
        val mid = (low + high) / 2
        if (this[mid] <= coordinate) low = mid else high = mid
    }
    return low
}

/**
 * Where [coordinate] sits between nodes [lowIndex] and [highIndex], as a fraction in `[0, 1]`.
 *
 * Returns `0.0` when the two indices coincide, which is how the top and bottom of the grid clamp: past
 * the last centre there is no next node to interpolate towards, so the last measured value stands. The
 * `coerceIn` does the same job below the first centre, where the raw fraction comes out negative.
 */
private fun DoubleArray.fractionBetween(
    lowIndex: Int,
    highIndex: Int,
    coordinate: Double,
): Double {
    if (lowIndex == highIndex) return 0.0
    val span = this[highIndex] - this[lowIndex]
    if (span <= 0.0) return 0.0
    return ((coordinate - this[lowIndex]) / span).coerceIn(0.0, 1.0)
}

/**
 * Builds a [TiledBackground] over [frame] with square tiles of [tileSizePx] pixels.
 *
 * The last tile in each direction absorbs the remainder rather than being dropped, so a frame whose
 * dimensions are not a multiple of the tile size still has every pixel covered by a measured tile. A
 * frame smaller than one tile yields a single tile, which degrades cleanly to a global estimate.
 *
 * Because that last tile is genuinely a different size, its interpolation node is placed at its real
 * centre rather than at the nominal `(index + 0.5) * tileSizePx` — see [TiledBackground.centresXPx] for
 * what goes wrong otherwise.
 *
 * Two passes, and they cannot be folded into one: the spread is measured on each pixel's residual against
 * the *interpolated* level, and that interpolation reads the neighbouring tiles' levels, which do not
 * exist until every tile's level has been measured. Both passes visit the same pixels in the same raster
 * order, so the result is a pure function of the frame.
 */
fun estimateTiledBackground(
    frame: LumaFrame,
    tileSizePx: Int = StarDetectorDefaults.BACKGROUND_TILE_SIZE_PX,
): TiledBackground {
    require(tileSizePx > 0) { "tileSizePx must be positive; was $tileSizePx" }
    val bandsX = tileBands(frame.widthPx, tileSizePx)
    val bandsY = tileBands(frame.heightPx, tileSizePx)
    val tilesX = bandsX.size
    val tilesY = bandsY.size
    val centresXPx = DoubleArray(tilesX) { bandsX[it].centrePx }
    val centresYPx = DoubleArray(tilesY) { bandsY[it].centrePx }
    val levels = DoubleArray(tilesX * tilesY)
    val sigmas = DoubleArray(tilesX * tilesY)

    // Pass 1: the level, from the tile's raw values. The median is where a ramp is harmless — over a
    // linear gradient the median of a tile *is* the level at that tile's centre, which is precisely the
    // node the interpolation places there — so subtracting anything first would only distort it.
    val histogram = IntArray(LUMA_LEVELS)
    for (tileY in 0 until tilesY) {
        for (tileX in 0 until tilesX) {
            histogram.fill(0)
            val sampleCount =
                forEachTilePixel(bandsX[tileX], bandsY[tileY]) { x, y ->
                    histogram[frame.lumaAt(x, y)] += 1
                }
            levels[tileY * tilesX + tileX] = histogram.quantile(sampleCount, MEDIAN_FRACTION)
        }
    }

    // The model the residuals are taken against. Built from the finished level grid and the real tile
    // centres, so the level subtracted from a pixel is exactly the one the detector will threshold that
    // pixel against — including the half-pixel centre convention and the flat clamp outside the outermost
    // centres. Its sigmas are still zero and are never read; the second pass only calls `levelAt`.
    val interpolatedLevel =
        TiledBackground(
            nominalTileSizePx = tileSizePx,
            tilesX = tilesX,
            tilesY = tilesY,
            centresXPx = centresXPx,
            centresYPx = centresYPx,
            levels = levels,
            sigmas = DoubleArray(levels.size),
        )

    // Pass 2: the spread, on the background-subtracted residual rather than on the raw values, so an
    // in-tile ramp is removed before it can be counted as noise. Still median minus lower quartile, so a
    // tile full of stars still reports the noise of its sky; what changed is what the two quantiles are
    // measured around, and that they are now read between bins rather than snapped to one — see
    // [interpolatedQuantile] for why the spread cannot afford that rounding once the ramp is gone.
    val residuals = IntArray(RESIDUAL_LEVELS)
    for (tileY in 0 until tilesY) {
        for (tileX in 0 until tilesX) {
            residuals.fill(0)
            val sampleCount =
                forEachTilePixel(bandsX[tileX], bandsY[tileY]) { x, y ->
                    residuals[residualBin(frame.lumaAt(x, y) - interpolatedLevel.levelAt(x, y))] += 1
                }
            // A difference of two bin indices is already a difference of residual luma, so the bias the
            // bins are offset by cancels and never has to be undone.
            val median = residuals.interpolatedQuantile(sampleCount, MEDIAN_FRACTION)
            val lowerQuartile = residuals.interpolatedQuantile(sampleCount, LOWER_QUARTILE_FRACTION)
            sigmas[tileY * tilesX + tileX] =
                ((median - lowerQuartile) / NORMAL_QUARTILE_SIGMAS).coerceAtLeast(0.0)
        }
    }

    return TiledBackground(
        nominalTileSizePx = tileSizePx,
        tilesX = tilesX,
        tilesY = tilesY,
        centresXPx = centresXPx,
        centresYPx = centresYPx,
        levels = levels,
        sigmas = sigmas,
    )
}

/**
 * Runs [body] over every pixel of the tile that [column] and [band] intersect at, in raster order, and
 * returns how many pixels that was.
 *
 * Both passes walk a tile identically and differ only in what they accumulate, so the walk — including
 * the remainder band's real, wider extent — is stated once and cannot drift between them.
 */
private inline fun forEachTilePixel(
    column: TileBand,
    band: TileBand,
    body: (x: Int, y: Int) -> Unit,
): Int {
    for (y in band.startPx until band.endPx) {
        for (x in column.startPx until column.endPx) {
            body(x, y)
        }
    }
    return (column.endPx - column.startPx) * (band.endPx - band.startPx)
}

/**
 * One tile's real extent along one axis, in raster indices, plus the continuous coordinate of its
 * centre.
 *
 * [centrePx] is derived from [startPx]/[endPx] and never from the nominal tile size, which is what
 * makes a remainder tile's interpolation node land where its pixels actually are.
 */
private class TileBand(
    val startPx: Int,
    val endPx: Int,
) {
    /**
     * The band's centre in continuous buffer-pixel coordinates. A band covering raster samples
     * `[start, end)` occupies the continuous span `[start, end)` under the edge-coordinate convention,
     * so its centre is the midpoint of those two edges.
     */
    val centrePx: Double get() = (startPx + endPx) / 2.0
}

/**
 * Splits an axis of [lengthPx] raster samples into bands of nominally [tileSizePx], with the final band
 * absorbing the remainder so no strip of the frame goes unmeasured and later gets thresholded against a
 * background it was never sampled for.
 */
private fun tileBands(
    lengthPx: Int,
    tileSizePx: Int,
): List<TileBand> {
    val count = ((lengthPx + tileSizePx - 1) / tileSizePx).coerceAtLeast(1)
    return (0 until count).map { index ->
        val start = index * tileSizePx
        val end = if (index == count - 1) lengthPx else ((index + 1) * tileSizePx).coerceAtMost(lengthPx)
        TileBand(startPx = start, endPx = end)
    }
}

/** 8-bit luma has exactly this many distinct values, so a histogram of this width is exact, not binned. */
private const val LUMA_LEVELS = 256

/**
 * A residual `pixel - interpolatedLevel` runs over `(-255, 255)`, so this many 1-luma bins cover it with
 * no clipping. The bin width is the grid the pixels themselves are quantised to; finer bins would only
 * record where a rounding sat, not anything the sensor measured. [interpolatedQuantile] is what recovers
 * sub-bin resolution, and it recovers it from the counts rather than from the bin width.
 */
private const val RESIDUAL_LEVELS = 2 * LUMA_LEVELS - 1

/** Shifts a residual of `-255` onto bin 0. Cancels in `median - q25` and so is never subtracted back. */
private const val RESIDUAL_BIAS = LUMA_LEVELS - 1

/**
 * The [RESIDUAL_LEVELS]-bin index for a residual of [residual] luma. The `coerceIn` cannot fire for an
 * interpolated level that came from real 8-bit medians, and is there so a future change to how the level
 * is produced degrades to a clamped bin rather than to an out-of-bounds write.
 */
private fun residualBin(residual: Double): Int =
    (residual.roundToInt() + RESIDUAL_BIAS).coerceIn(0, RESIDUAL_LEVELS - 1)

/** Pixel `(x, y)` spans `[x, x+1)`, so its centre is half a pixel in. */
private const val PIXEL_CENTRE_OFFSET = 0.5

private const val MEDIAN_FRACTION = 0.5

private const val LOWER_QUARTILE_FRACTION = 0.25

/**
 * For a standard normal, the distance from the median to either quartile is `0.674490` sigma. Dividing
 * the observed `median - q25` by it converts a robust, star-immune spread into the sigma the threshold
 * is expressed in.
 */
private const val NORMAL_QUARTILE_SIGMAS = 0.6744897501960817

/**
 * The [fraction]-quantile of a histogram holding [sampleCount] samples, as a bin index, by linear search
 * over the cumulative counts. Returns `0.0` for an empty tile, which cannot occur for a frame with
 * positive dimensions but is defined rather than left to divide by zero.
 */
private fun IntArray.quantile(
    sampleCount: Int,
    fraction: Double,
): Double {
    if (sampleCount <= 0) return 0.0
    val target = (fraction * sampleCount).toInt().coerceIn(0, sampleCount - 1)
    var cumulative = 0
    for (level in indices) {
        cumulative += this[level]
        if (cumulative > target) return level.toDouble()
    }
    return (size - 1).toDouble()
}

/**
 * The [fraction]-quantile of a histogram holding [sampleCount] samples, as a **fractional** bin index:
 * the same statistic as [quantile] but interpolated between the mid-points of the occupied bins instead
 * of snapping to whichever bin the cumulative count crosses in.
 *
 * The spread needs this and the level does not. `sigma = (median - q25) / 0.6745` divides a difference of
 * two bin indices by 0.674, so a whole-bin answer can only ever report a multiple of 1.48 luma: a sky
 * whose true sigma is 2.5 has `median - q25 = 1.69` and lands on 1 or on 2 depending on which side of the
 * bin edge a tile's noise realisation falls, i.e. on 1.48 or 2.97 — a 40% error either way, and on the
 * low side it puts the 4-sigma threshold at 2.4 real sigmas, where noise clears it often enough to build
 * three-pixel clumps the detector then reports as sources. Measuring the spread on the residual removed
 * the gradient that used to hide that coarseness behind a large sigma, so it has to be resolved rather
 * than inherited. The level is a luma value on the pixel grid and stays on it.
 *
 * Occupied bins are interpolated at their **mid**-cumulative position — a bin holding `c` of the samples
 * covers cumulative `[before, before + c]` and is placed at `before + c/2` — which is the standard
 * continuity correction for a quantised sample. A tile whose pixels are all one value has one occupied
 * bin, every target clamps onto it, and the spread comes out exactly zero, so a noiseless sky still
 * reports no spread at all rather than the half-bin an edge-based interpolation would invent.
 */
private fun IntArray.interpolatedQuantile(
    sampleCount: Int,
    fraction: Double,
): Double {
    if (sampleCount <= 0) return 0.0
    val target = fraction * sampleCount
    var cumulative = 0
    var previousBin = -1
    var previousMidpoint = 0.0
    for (bin in indices) {
        val count = this[bin]
        if (count == 0) continue
        val midpoint = cumulative + count / 2.0
        if (target <= midpoint) {
            if (previousBin < 0) return bin.toDouble()
            return previousBin + (target - previousMidpoint) / (midpoint - previousMidpoint) * (bin - previousBin)
        }
        cumulative += count
        previousBin = bin
        previousMidpoint = midpoint
    }
    return previousBin.coerceAtLeast(0).toDouble()
}
