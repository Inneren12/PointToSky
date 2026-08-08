package dev.pointtosky.core.astro.projection.camera.detect

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
 * above them.
 *
 * The two are read from different things because they are measurements of different things. The level is
 * a raw 8-bit luma, whose only possible values are the 256 integers, so it comes from a 256-bin histogram
 * that is exact by construction and needs no sort. The spread is a statistic of the residual
 * `pixel - levelAt(pixel centre)`, which is a real number — the interpolated level is a `Double` and lands
 * between luma levels almost everywhere — so it is taken from the residual samples themselves, sorted.
 * Rounding those residuals onto a luma grid first would discard exactly the sub-luma information the
 * spread is made of, and no amount of interpolating between integer bins afterwards can put it back.
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
 * How finely the spread can resolve follows from how much the level varies inside the tile. Over a
 * gradient the interpolated level sweeps several luma across a tile, the residuals land all over the real
 * line, and the quartile is measured on a genuinely continuous sample. Over a flat sky the level is
 * constant across the tile, the residuals are integers minus that constant, and the quartile can only
 * land on one of them — so `sigma` there is quantised to multiples of `1 / 0.6745 = 1.48` luma, exactly
 * as it was before any of this. That is a property of an 8-bit sensor, not of the estimator, and it is
 * why [StarDetectorConfig.minThresholdAboveBackground] exists.
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
     * remainder and is therefore a partial tile — narrower or shorter than this — whenever the frame is
     * not an exact multiple of it. Nothing in the interpolation reads this value — see [centresXPx].
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
            forEachTilePixel(bandsX[tileX], bandsY[tileY]) { x, y ->
                histogram[frame.lumaAt(x, y)] += 1
            }
            val sampleCount = bandsX[tileX].lengthPx * bandsY[tileY].lengthPx
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
    // tile full of stars still reports the noise of its sky; what changed is only what the two quantiles
    // are measured around.
    //
    // The residuals are held and sorted as the real numbers they are, not binned. A tile is at most
    // `tileSizePx` square — 4096 samples at the default — so one reused buffer and one sort per tile costs
    // nothing worth trading a rounding error against, and `DoubleArray.sort` is a fixed algorithm over a
    // fixed input, so the result is reproducible to the bit.
    val residuals = DoubleArray(bandsX.maxOf { it.lengthPx } * bandsY.maxOf { it.lengthPx })
    for (tileY in 0 until tilesY) {
        for (tileX in 0 until tilesX) {
            var sampleCount = 0
            forEachTilePixel(bandsX[tileX], bandsY[tileY]) { x, y ->
                residuals[sampleCount] = frame.lumaAt(x, y) - interpolatedLevel.levelAt(x, y)
                sampleCount += 1
            }
            residuals.sort(fromIndex = 0, toIndex = sampleCount)
            val median = residuals.orderStatistic(sampleCount, MEDIAN_FRACTION)
            val lowerQuartile = residuals.orderStatistic(sampleCount, LOWER_QUARTILE_FRACTION)
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
 * Runs [body] over every pixel of the tile that [column] and [band] intersect at, in raster order.
 *
 * Both passes walk a tile identically and differ only in what they accumulate, so the walk — including
 * the remainder band's actual extent, which is a partial tile — is stated once and cannot drift between
 * them.
 */
private inline fun forEachTilePixel(
    column: TileBand,
    band: TileBand,
    body: (x: Int, y: Int) -> Unit,
) {
    for (y in band.startPx until band.endPx) {
        for (x in column.startPx until column.endPx) {
            body(x, y)
        }
    }
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
    /** How many raster samples this band covers. The remainder band is a partial tile and covers fewer. */
    val lengthPx: Int get() = endPx - startPx

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
 * The [fraction]-quantile of the [sampleCount] values sorted into the front of this array, by nearest
 * rank — the same convention [quantile] reaches on the level histogram, so the level and the spread are
 * not quietly reading their quantiles two different ways.
 *
 * No interpolation between neighbouring order statistics. These samples are already the real residuals
 * rather than bin labels, so the rank is the only approximation left, and it is the one a quartile
 * estimate on thousands of samples can afford. Returns `0.0` for an empty tile, which a frame with
 * positive dimensions cannot produce but which is defined rather than left to index out of bounds.
 */
private fun DoubleArray.orderStatistic(
    sampleCount: Int,
    fraction: Double,
): Double {
    if (sampleCount <= 0) return 0.0
    return this[(fraction * sampleCount).toInt().coerceIn(0, sampleCount - 1)]
}
