package dev.pointtosky.core.astro.projection.camera.detect

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * SKY-2 **test/dev utility**: renders synthetic RAW_Y8 luma frames with stars in them.
 *
 * This lives in the test source set and must stay there. It is not a product feature, nothing in the
 * shipped app path may call it, and a frame it produces must never be written into a session log — a
 * synthetic frame that reached a log directory would be indistinguishable from a captured one and would
 * quietly poison every measurement made from that session.
 *
 * It exists because the SKY-1 fixtures cannot serve as detector inputs. `SkySessionLogFixtures` builds a
 * [dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaReference] — a path, a geometry, and a byte
 * length — and no pixels at all, because the replay it was written for compares recomputed projections
 * against recorded ones and never opens a frame file. A detector needs the pixels, so the pixels have to
 * be manufactured, with the star positions known exactly so a centroid can be scored against truth
 * rather than against another estimate.
 *
 * ## Why the PSF is integrated rather than sampled
 * A star is rendered by integrating a 2-D Gaussian **over each pixel's area**, not by evaluating it at
 * the pixel centre. Point sampling a profile only ~3 pixels wide biases the reconstructed centroid
 * toward the centre of whichever pixel the star happens to sit in, by up to a few hundredths of a pixel.
 * That is the same order as the sub-pixel accuracy the detector is being tested for, so a point-sampled
 * fixture would be measuring the renderer's discretisation error and calling it the detector's. The
 * integral is separable, so each pixel costs two error-function differences.
 *
 * Everything is deterministic: noise comes from a seeded [Random], never from a global source.
 */

/** A star to render, positioned in the same top-left-origin pixel space the detector reports. */
internal data class SyntheticStar(
    /** Sub-pixel centre. `(0.5, 0.5)` is the centre of pixel `(0, 0)`. */
    val xPx: Double,
    val yPx: Double,
    /**
     * Peak amplitude of the *continuous* Gaussian, in luma levels above the background. The brightest
     * rendered pixel comes out slightly below this because it holds the profile's average over its area
     * rather than its peak; a star with a peak that would exceed 255 is clipped, which is exactly how a
     * saturated star is produced.
     */
    val peakAboveBackground: Double,
    /** Full width at half maximum, in pixels — the usual way seeing/defocus is quoted. */
    val fwhmPx: Double = DEFAULT_FWHM_PX,
) {
    init {
        require(peakAboveBackground > 0.0) { "peakAboveBackground must be positive; was $peakAboveBackground" }
        require(fwhmPx > 0.0) { "fwhmPx must be positive; was $fwhmPx" }
    }

    val sigmaPx: Double get() = fwhmPx / FWHM_PER_SIGMA
}

/** A single stuck-bright sensor pixel: no spatial extent at all, which is what makes it rejectable. */
internal data class SyntheticHotPixel(
    val x: Int,
    val y: Int,
    val luma: Int = HOT_PIXEL_LUMA,
)

/** The sky level under the stars, before noise. */
internal sealed interface SyntheticBackground {
    fun levelAt(
        x: Int,
        y: Int,
    ): Double

    /** Flat sky — the easy case, and the one that would let a global threshold pass a test it should not. */
    data class Uniform(
        val level: Double,
    ) : SyntheticBackground {
        override fun levelAt(
            x: Int,
            y: Int,
        ): Double = level
    }

    /**
     * A linear light-pollution ramp from [levelAtOrigin] to [levelAtOpposite] along the diagonal — the
     * realistic case, and the one that separates a local background estimate from a global one.
     */
    data class LinearGradient(
        val levelAtOrigin: Double,
        val levelAtOpposite: Double,
        val widthPx: Int,
        val heightPx: Int,
    ) : SyntheticBackground {
        override fun levelAt(
            x: Int,
            y: Int,
        ): Double {
            val fraction = (x.toDouble() / widthPx + y.toDouble() / heightPx) / 2.0
            return levelAtOrigin + (levelAtOpposite - levelAtOrigin) * fraction
        }
    }
}

/** Sensor noise added on top of the background and stars. */
internal sealed interface SyntheticNoise {
    /** Adds noise at ([x], [y]) to an already-computed [value], drawing from [random]. */
    fun applyTo(
        value: Double,
        random: Random,
    ): Double

    /** No noise at all — proves the detector's absolute threshold floor holds when sigma measures zero. */
    data object None : SyntheticNoise {
        override fun applyTo(
            value: Double,
            random: Random,
        ): Double = value
    }

    /** Read noise: additive, signal-independent, the model the sigma threshold is derived for. */
    data class Gaussian(
        val sigma: Double,
    ) : SyntheticNoise {
        override fun applyTo(
            value: Double,
            random: Random,
        ): Double = value + random.nextGaussian() * sigma
    }

    /**
     * Shot noise: the count itself is Poisson, so the spread grows as the square root of the signal.
     * Real sky-limited astrophotography is dominated by this, and it is the case where a bright region
     * of a gradient is genuinely noisier than a dark one — which a single global sigma cannot express.
     */
    data object Poisson : SyntheticNoise {
        override fun applyTo(
            value: Double,
            random: Random,
        ): Double = random.nextPoisson(value.coerceAtLeast(0.0)).toDouble()
    }
}

/**
 * Renders one frame.
 *
 * Bytes in the row padding between [widthPx] and [rowStridePx] are filled with
 * [ROW_PADDING_FILL_LUMA] — a saturated value that is nowhere near any background this renderer
 * produces. A detector that ignored the stride and read the buffer as tightly packed would pull that
 * padding in as image content and report a column of bright sources that no test placed there, so the
 * fill turns a stride bug into a loud failure instead of a subtle centroid shift.
 */
internal fun renderSyntheticFrame(
    widthPx: Int,
    heightPx: Int,
    rowStridePx: Int = widthPx,
    background: SyntheticBackground = SyntheticBackground.Uniform(DEFAULT_BACKGROUND_LUMA),
    stars: List<SyntheticStar> = emptyList(),
    hotPixels: List<SyntheticHotPixel> = emptyList(),
    noise: SyntheticNoise = SyntheticNoise.None,
    seed: Long = DEFAULT_SEED,
): LumaFrame =
    LumaFrame(
        data = renderSyntheticFrameData(widthPx, heightPx, rowStridePx, background, stars, hotPixels, noise, seed),
        widthPx = widthPx,
        heightPx = heightPx,
        rowStridePx = rowStridePx,
    )

/**
 * The same render as [renderSyntheticFrame], returned as the raw RAW_Y8 bytes a session-log frame file
 * would hold. Used by tests that go through [LumaFrame.forReference] to prove the detector reads a frame
 * described by a real [dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaReference].
 */
internal fun renderSyntheticFrameData(
    widthPx: Int,
    heightPx: Int,
    rowStridePx: Int = widthPx,
    background: SyntheticBackground = SyntheticBackground.Uniform(DEFAULT_BACKGROUND_LUMA),
    stars: List<SyntheticStar> = emptyList(),
    hotPixels: List<SyntheticHotPixel> = emptyList(),
    noise: SyntheticNoise = SyntheticNoise.None,
    seed: Long = DEFAULT_SEED,
): ByteArray {
    require(rowStridePx >= widthPx) { "rowStridePx ($rowStridePx) must be >= widthPx ($widthPx)" }
    val random = Random(seed)
    val data = ByteArray(rowStridePx * heightPx) { ROW_PADDING_FILL_LUMA.toByte() }

    for (y in 0 until heightPx) {
        for (x in 0 until widthPx) {
            var value = background.levelAt(x, y)
            for (star in stars) {
                value += star.contributionTo(x, y)
            }
            value = noise.applyTo(value, random)
            data[y * rowStridePx + x] = value.roundToInt().coerceIn(0, MAX_LUMA).toByte()
        }
    }

    for (hotPixel in hotPixels) {
        require(hotPixel.x in 0 until widthPx && hotPixel.y in 0 until heightPx) {
            "hot pixel (${hotPixel.x}, ${hotPixel.y}) is outside the ${widthPx}x$heightPx frame"
        }
        data[hotPixel.y * rowStridePx + hotPixel.x] = hotPixel.luma.coerceIn(0, MAX_LUMA).toByte()
    }

    return data
}

/** The truth positions of [stars], in the form [evaluateDetections] scores against. */
internal fun List<SyntheticStar>.toPredictedPoints(): List<PredictedPointPx> =
    mapIndexed { index, star -> PredictedPointPx(catalogIndex = index, xPx = star.xPx, yPx = star.yPx) }

/**
 * This star's flux inside pixel ([x], [y]), as the exact integral of its Gaussian over the pixel's unit
 * area, scaled so that the continuous peak equals [SyntheticStar.peakAboveBackground].
 */
private fun SyntheticStar.contributionTo(
    x: Int,
    y: Int,
): Double {
    val sigma = sigmaPx
    // Beyond this many sigmas the integral is under a millionth of the peak and cannot move an 8-bit
    // pixel, so the cutoff keeps a large frame's render from being O(pixels * stars) over its whole area.
    if (abs(x + PIXEL_CENTRE - xPx) > PSF_CUTOFF_SIGMAS * sigma) return 0.0
    if (abs(y + PIXEL_CENTRE - yPx) > PSF_CUTOFF_SIGMAS * sigma) return 0.0
    val fractionX = normalMass(x.toDouble(), x + 1.0, xPx, sigma)
    val fractionY = normalMass(y.toDouble(), y + 1.0, yPx, sigma)
    // Each normalMass is a probability mass; a unit-height Gaussian has area sigma*sqrt(2*PI) per axis,
    // so multiplying back by that in both axes recovers a profile whose continuous peak is exactly
    // peakAboveBackground.
    return peakAboveBackground * fractionX * fractionY * TWO_PI * sigma * sigma
}

/** The mass a normal distribution of centre [mean] and spread [sigma] places on `[from, to)`. */
private fun normalMass(
    from: Double,
    to: Double,
    mean: Double,
    sigma: Double,
): Double = standardNormalCdf((to - mean) / sigma) - standardNormalCdf((from - mean) / sigma)

private fun standardNormalCdf(z: Double): Double = (1.0 + erf(z / SQRT_TWO)) / 2.0

/**
 * Abramowitz & Stegun 7.1.26. Absolute error below 1.5e-7, which is four orders of magnitude finer than
 * the 1/255 quantisation the rendered pixel is rounded to, so it contributes nothing measurable to a
 * centroid.
 */
private fun erf(x: Double): Double {
    val t = 1.0 / (1.0 + ERF_P * abs(x))
    val polynomial = t * (ERF_A1 + t * (ERF_A2 + t * (ERF_A3 + t * (ERF_A4 + t * ERF_A5))))
    return sign(x) * (1.0 - polynomial * exp(-x * x))
}

/** Box-Muller. One of the pair is discarded, which costs nothing and keeps the draw stateless. */
private fun Random.nextGaussian(): Double {
    val u1 = nextDouble().coerceAtLeast(MIN_UNIFORM)
    val u2 = nextDouble()
    return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(TWO_PI * u2)
}

/**
 * Knuth's multiplication method. Exact for the lambdas a luma frame produces (a background of a few tens
 * of counts); it would underflow past lambda ~700, which no 8-bit pixel can reach.
 */
private fun Random.nextPoisson(lambda: Double): Int {
    val limit = exp(-lambda)
    var count = 0
    var product = nextDouble()
    while (product > limit) {
        count += 1
        product *= nextDouble()
    }
    return count
}

private const val MAX_LUMA = 255

/** Saturated fill for stride padding, so reading it instead of real pixels fails loudly. */
internal const val ROW_PADDING_FILL_LUMA = 255

internal const val DEFAULT_BACKGROUND_LUMA = 24.0

internal const val HOT_PIXEL_LUMA = 250

private const val DEFAULT_FWHM_PX = 3.0

private const val DEFAULT_SEED = 20260808L

/** `FWHM = 2 * sqrt(2 * ln 2) * sigma` for a Gaussian. */
private const val FWHM_PER_SIGMA = 2.354820045030949

private const val PSF_CUTOFF_SIGMAS = 6.0

private const val PIXEL_CENTRE = 0.5

private const val TWO_PI = 6.283185307179586

private const val SQRT_TWO = 1.4142135623730951

private const val MIN_UNIFORM = 1.0e-12

private const val ERF_P = 0.3275911
private const val ERF_A1 = 0.254829592
private const val ERF_A2 = -0.284496736
private const val ERF_A3 = 1.421413741
private const val ERF_A4 = -1.453152027
private const val ERF_A5 = 1.061405429
