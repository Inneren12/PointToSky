package dev.pointtosky.core.astro.projection.camera.detect

import kotlin.math.floor

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
 * above them. Both statistics come from a 256-bin histogram, which is exact for 8-bit data and needs no
 * sort.
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
 *
 * ## Known limitation: a steep gradient inflates sigma
 * The spread is measured over a tile's *raw* pixels, so whatever the gradient itself ramps across one
 * tile is counted as noise on top of the real per-pixel noise. On a strong light-pollution gradient this
 * can report a sigma several times the true one and push the threshold correspondingly higher. The error
 * is one-sided and conservative — the detector loses sensitivity to the faintest stars there, it does not
 * gain false positives — so the baseline accepts it. Fixing it means subtracting the interpolated level
 * before measuring the spread, which is a refinement with its own convergence question and is not part of
 * this stage.
 */
class TiledBackground internal constructor(
    val tileSizePx: Int,
    val tilesX: Int,
    val tilesY: Int,
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

    /**
     * Bilinear interpolation over the tile-centre grid, clamped at the border tiles.
     *
     * The `- 0.5` puts pixel centres on the same footing as tile centres: tile `t` is centred at
     * `(t + 0.5) * tileSizePx` in pixel coordinates, so a pixel at that location must land exactly on
     * grid node `t` with zero fractional weight. Clamping the node indices makes the model constant
     * outside the outermost centres instead of extrapolating a gradient past the last measurement.
     */
    private fun interpolate(
        grid: DoubleArray,
        x: Int,
        y: Int,
    ): Double {
        val gx = (x + PIXEL_CENTRE_OFFSET) / tileSizePx - TILE_CENTRE_OFFSET
        val gy = (y + PIXEL_CENTRE_OFFSET) / tileSizePx - TILE_CENTRE_OFFSET
        val x0 = floor(gx).toInt().coerceIn(0, tilesX - 1)
        val y0 = floor(gy).toInt().coerceIn(0, tilesY - 1)
        val x1 = (x0 + 1).coerceAtMost(tilesX - 1)
        val y1 = (y0 + 1).coerceAtMost(tilesY - 1)
        val fx = (gx - x0).coerceIn(0.0, 1.0)
        val fy = (gy - y0).coerceIn(0.0, 1.0)

        val top = grid[y0 * tilesX + x0] * (1.0 - fx) + grid[y0 * tilesX + x1] * fx
        val bottom = grid[y1 * tilesX + x0] * (1.0 - fx) + grid[y1 * tilesX + x1] * fx
        return top * (1.0 - fy) + bottom * fy
    }
}

/**
 * Builds a [TiledBackground] over [frame] with square tiles of [tileSizePx] pixels.
 *
 * The last tile in each direction absorbs the remainder rather than being dropped, so a frame whose
 * dimensions are not a multiple of the tile size still has every pixel covered by a measured tile. A
 * frame smaller than one tile yields a single tile, which degrades cleanly to a global estimate.
 */
fun estimateTiledBackground(
    frame: LumaFrame,
    tileSizePx: Int = StarDetectorDefaults.BACKGROUND_TILE_SIZE_PX,
): TiledBackground {
    require(tileSizePx > 0) { "tileSizePx must be positive; was $tileSizePx" }
    val tilesX = ((frame.widthPx + tileSizePx - 1) / tileSizePx).coerceAtLeast(1)
    val tilesY = ((frame.heightPx + tileSizePx - 1) / tileSizePx).coerceAtLeast(1)
    val levels = DoubleArray(tilesX * tilesY)
    val sigmas = DoubleArray(tilesX * tilesY)
    val histogram = IntArray(LUMA_LEVELS)

    for (tileY in 0 until tilesY) {
        val yStart = tileY * tileSizePx
        val yEnd = tileEndPx(tileY, tilesY, tileSizePx, frame.heightPx)
        for (tileX in 0 until tilesX) {
            val xStart = tileX * tileSizePx
            val xEnd = tileEndPx(tileX, tilesX, tileSizePx, frame.widthPx)

            histogram.fill(0)
            var sampleCount = 0
            for (y in yStart until yEnd) {
                for (x in xStart until xEnd) {
                    histogram[frame.lumaAt(x, y)] += 1
                    sampleCount += 1
                }
            }

            val index = tileY * tilesX + tileX
            val median = histogram.quantile(sampleCount, MEDIAN_FRACTION)
            val lowerQuartile = histogram.quantile(sampleCount, LOWER_QUARTILE_FRACTION)
            levels[index] = median
            sigmas[index] = ((median - lowerQuartile) / NORMAL_QUARTILE_SIGMAS).coerceAtLeast(0.0)
        }
    }
    return TiledBackground(tileSizePx, tilesX, tilesY, levels, sigmas)
}

/**
 * Where tile [index] of [tileCount] stops along one axis, exclusive.
 *
 * The last tile runs all the way to [limitPx] instead of stopping at its nominal edge, so a frame whose
 * size is not a multiple of [tileSizePx] has no unmeasured strip along its right or bottom border — which
 * would otherwise be thresholded against an extrapolated background it was never sampled for.
 */
private fun tileEndPx(
    index: Int,
    tileCount: Int,
    tileSizePx: Int,
    limitPx: Int,
): Int = if (index == tileCount - 1) limitPx else ((index + 1) * tileSizePx).coerceAtMost(limitPx)

/** 8-bit luma has exactly this many distinct values, so a histogram of this width is exact, not binned. */
private const val LUMA_LEVELS = 256

/** Pixel `(x, y)` spans `[x, x+1)`, so its centre is half a pixel in. */
private const val PIXEL_CENTRE_OFFSET = 0.5

/** Tile `t` covers `[t * tileSizePx, (t+1) * tileSizePx)`, so its centre node sits half a tile in. */
private const val TILE_CENTRE_OFFSET = 0.5

private const val MEDIAN_FRACTION = 0.5

private const val LOWER_QUARTILE_FRACTION = 0.25

/**
 * For a standard normal, the distance from the median to either quartile is `0.674490` sigma. Dividing
 * the observed `median - q25` by it converts a robust, star-immune spread into the sigma the threshold
 * is expressed in.
 */
private const val NORMAL_QUARTILE_SIGMAS = 0.6744897501960817

/**
 * The [fraction]-quantile of a 256-bin luma histogram holding [sampleCount] samples, by linear search
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
