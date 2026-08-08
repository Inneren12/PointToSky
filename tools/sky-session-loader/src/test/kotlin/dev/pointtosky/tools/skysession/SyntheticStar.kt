package dev.pointtosky.tools.skysession

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Test-only RAW_Y8 renderer: stars at known sub-pixel positions, on a background, with seeded noise.
 *
 * SKY-2 has one of these (`SyntheticFrameRenderer` in `:core:astro-core`'s **test** source set) and
 * this is deliberately the same math — integrate the Gaussian over each pixel's area rather than
 * sampling it at the centre, so the fixture's own discretisation error stays an order of magnitude
 * below the sub-pixel accuracy being measured. It is duplicated rather than shared because that one is
 * `internal` to a test source set, and reaching it from here would mean publishing test fixtures out of
 * `:core:astro-core` — a change to the module this brief exists to leave alone.
 *
 * Nothing here may be used outside tests: a synthetic frame written into a real session directory would
 * be indistinguishable from a captured one and would poison every measurement made from that session.
 */
internal data class SyntheticStar(
    /**
     * Sub-pixel centre in the project's continuous edge-coordinate convention — raster sample `[x, y]`
     * is centred at `(x + 0.5, y + 0.5)`, the same convention `DetectedSource.xPx` and
     * `SkyPredictedStar.imageXPx` are expressed in.
     */
    val xPx: Double,
    val yPx: Double,
    val peakAboveBackground: Double,
    val fwhmPx: Double = DEFAULT_FWHM_PX,
) {
    val sigmaPx: Double get() = fwhmPx / FWHM_PER_SIGMA
}

/**
 * The bytes a `frames/frame_NNNNNN.y` file holds: `rowStridePx * heightPx` of packed 8-bit luma.
 *
 * Row padding between [widthPx] and [rowStridePx] is filled saturated, so a reader that ignored the
 * stride would pull a column of phantom sources into the image instead of failing quietly.
 */
internal fun renderLumaBytes(
    widthPx: Int,
    heightPx: Int,
    rowStridePx: Int = widthPx,
    backgroundLuma: Double = DEFAULT_BACKGROUND_LUMA,
    stars: List<SyntheticStar> = emptyList(),
    noiseSigma: Double = 0.0,
    seed: Long = DEFAULT_SEED,
): ByteArray {
    require(rowStridePx >= widthPx) { "rowStridePx ($rowStridePx) must be >= widthPx ($widthPx)" }
    val random = Random(seed)
    val data = ByteArray(rowStridePx * heightPx) { ROW_PADDING_FILL_LUMA.toByte() }
    for (y in 0 until heightPx) {
        for (x in 0 until widthPx) {
            var value = backgroundLuma
            for (star in stars) {
                value += star.contributionTo(x, y)
            }
            if (noiseSigma > 0.0) value += random.nextGaussian() * noiseSigma
            data[y * rowStridePx + x] = value.roundToInt().coerceIn(0, MAX_LUMA).toByte()
        }
    }
    return data
}

/** This star's flux inside pixel ([x], [y]): the exact integral of its Gaussian over the pixel's area. */
private fun SyntheticStar.contributionTo(
    x: Int,
    y: Int,
): Double {
    val sigma = sigmaPx
    if (abs(x + PIXEL_CENTRE - xPx) > PSF_CUTOFF_SIGMAS * sigma) return 0.0
    if (abs(y + PIXEL_CENTRE - yPx) > PSF_CUTOFF_SIGMAS * sigma) return 0.0
    val fractionX = normalMass(x.toDouble(), x + 1.0, xPx, sigma)
    val fractionY = normalMass(y.toDouble(), y + 1.0, yPx, sigma)
    return peakAboveBackground * fractionX * fractionY * TWO_PI * sigma * sigma
}

private fun normalMass(
    from: Double,
    to: Double,
    mean: Double,
    sigma: Double,
): Double = standardNormalCdf((to - mean) / sigma) - standardNormalCdf((from - mean) / sigma)

private fun standardNormalCdf(z: Double): Double = (1.0 + erf(z / SQRT_TWO)) / 2.0

/** Abramowitz & Stegun 7.1.26; absolute error below 1.5e-7, far finer than 8-bit quantisation. */
private fun erf(x: Double): Double {
    val t = 1.0 / (1.0 + ERF_P * abs(x))
    val polynomial = t * (ERF_A1 + t * (ERF_A2 + t * (ERF_A3 + t * (ERF_A4 + t * ERF_A5))))
    return sign(x) * (1.0 - polynomial * exp(-x * x))
}

/** Box-Muller, from a seeded source only — a fixture that varies run to run proves nothing. */
private fun Random.nextGaussian(): Double {
    val u1 = nextDouble().coerceAtLeast(MIN_UNIFORM)
    val u2 = nextDouble()
    return sqrt(-2.0 * ln(u1)) * cos(TWO_PI * u2)
}

private const val MAX_LUMA = 255

internal const val ROW_PADDING_FILL_LUMA = 255

internal const val DEFAULT_BACKGROUND_LUMA = 24.0

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
