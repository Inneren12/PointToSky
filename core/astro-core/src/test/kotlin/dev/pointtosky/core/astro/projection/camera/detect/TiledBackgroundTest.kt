package dev.pointtosky.core.astro.projection.camera.detect

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests the claims [TiledBackground]'s own documentation makes: that the level is robust to stars, that
 * the spread is read from below the median so stars cannot inflate it, and that interpolation removes
 * the tile seams a per-tile constant would leave behind.
 */
class TiledBackgroundTest {
    @Test
    fun `measures a flat sky as its own level with no spread`() {
        val frame =
            renderSyntheticFrame(
                widthPx = 128,
                heightPx = 128,
                background = SyntheticBackground.Uniform(37.0),
                noise = SyntheticNoise.None,
            )

        val model = estimateTiledBackground(frame, tileSizePx = 64)

        assertEquals(37.0, model.levelAt(10, 10))
        assertEquals(0.0, model.sigmaAt(10, 10), "a noiseless sky has no measurable spread")
    }

    @Test
    fun `recovers the injected noise sigma`() {
        val frame =
            renderSyntheticFrame(
                widthPx = 256,
                heightPx = 256,
                background = SyntheticBackground.Uniform(80.0),
                noise = SyntheticNoise.Gaussian(sigma = 6.0),
                seed = 3L,
            )

        val model = estimateTiledBackground(frame, tileSizePx = 64)

        // The quartile estimator is discretised by the 8-bit grid, so it lands near rather than on 6.0.
        assertTrue(abs(model.sigmaAt(128, 128) - 6.0) < 1.5, "estimated sigma was ${model.sigmaAt(128, 128)}")
        assertTrue(abs(model.levelAt(128, 128) - 80.0) < 1.0, "estimated level was ${model.levelAt(128, 128)}")
    }

    @Test
    fun `a bright star does not move the level or the spread of the tile it sits in`() {
        // This is the whole reason for the median and the lower quartile: a mean and a standard deviation
        // would both be pulled up by the star, raising the threshold that is supposed to find it.
        val starless =
            renderSyntheticFrame(
                widthPx = 128,
                heightPx = 128,
                background = SyntheticBackground.Uniform(40.0),
                noise = SyntheticNoise.Gaussian(sigma = 4.0),
                seed = 12L,
            )
        val withStar =
            renderSyntheticFrame(
                widthPx = 128,
                heightPx = 128,
                background = SyntheticBackground.Uniform(40.0),
                stars = listOf(SyntheticStar(xPx = 32.5, yPx = 32.5, peakAboveBackground = 200.0, fwhmPx = 5.0)),
                noise = SyntheticNoise.Gaussian(sigma = 4.0),
                seed = 12L,
            )

        val before = estimateTiledBackground(starless, tileSizePx = 64)
        val after = estimateTiledBackground(withStar, tileSizePx = 64)

        assertEquals(before.tileLevel(0, 0), after.tileLevel(0, 0), "a star must not move the tile level")
        // The star's pixels sit above the median and so never enter `median - q25` directly; all they can
        // do is take a few dozen of the tile's 4096 samples out of the sky's own distribution and move the
        // two quantiles by a fraction of the interpolation step between them. A mean and a standard
        // deviation would report roughly five times the sky's spread on this same frame.
        assertEquals(
            before.tileSigma(0, 0),
            after.tileSigma(0, 0),
            0.1,
            "a star must not inflate the tile spread",
        )
    }

    @Test
    fun `interpolates across tile seams instead of stepping`() {
        val frame =
            renderSyntheticFrame(
                widthPx = 256,
                heightPx = 64,
                background =
                    SyntheticBackground.LinearGradient(
                        levelAtOrigin = 10.0,
                        levelAtOpposite = 220.0,
                        widthPx = 256,
                        heightPx = 64,
                    ),
                noise = SyntheticNoise.None,
            )

        val model = estimateTiledBackground(frame, tileSizePx = 64)

        // Walk the row and require every neighbouring pair to differ by a small, smooth amount. A per-tile
        // constant model would sit flat and then jump by a whole tile's worth of ramp at each boundary,
        // and that jump is what manufactures false sources along the seam.
        var maxStep = 0.0
        for (x in 1 until 256) {
            maxStep = maxOf(maxStep, abs(model.levelAt(x, 32) - model.levelAt(x - 1, 32)))
        }
        assertTrue(maxStep < 1.0, "the interpolated background stepped by $maxStep at a tile seam")

        // And it must still actually follow the ramp rather than being smooth by being flat.
        assertTrue(model.levelAt(250, 32) - model.levelAt(5, 32) > 50.0, "the model must track the gradient")
    }

    @Test
    fun `covers a frame whose size is not a multiple of the tile`() {
        val frame =
            renderSyntheticFrame(
                widthPx = 100,
                heightPx = 70,
                background = SyntheticBackground.Uniform(55.0),
                noise = SyntheticNoise.None,
            )

        val model = estimateTiledBackground(frame, tileSizePx = 64)

        assertEquals(2, model.tilesX)
        assertEquals(2, model.tilesY)
        // The remainder strip belongs to the last tile, so it is measured, not extrapolated.
        for (tileY in 0 until model.tilesY) {
            for (tileX in 0 until model.tilesX) {
                assertEquals(55.0, model.tileLevel(tileX, tileY), "tile ($tileX, $tileY) was not measured")
            }
        }
        assertEquals(55.0, model.levelAt(99, 69))
    }

    @Test
    fun `places the remainder tile's node at its actual centre, not its nominal one`() {
        // width=100 at a nominal 64 px tile: column 0 spans [0,64) centred at 32, column 1 spans
        // [64,100) centred at 82. The nominal formula (index + 0.5) * 64 would put column 1's node at
        // 96 — 14 px to the right of the pixels it was measured over.
        val frame =
            renderSyntheticFrame(
                widthPx = 100,
                heightPx = 32,
                background = SyntheticBackground.Uniform(50.0),
                noise = SyntheticNoise.None,
            )

        val model = estimateTiledBackground(frame, tileSizePx = 64)

        assertEquals(2, model.tilesX)
        assertEquals(32.0, model.tileCentreXPx(0))
        assertEquals(82.0, model.tileCentreXPx(1), "the remainder column spans [64,100) and is centred at 82")
    }

    @Test
    fun `reaches the final tile value at the remainder tile's actual centre on a horizontal gradient`() {
        // A pure horizontal ramp, so the interpolated model can be read directly as "where does this
        // value belong". The old nominal-centre interpolation reaches tile 1's value only at x=96 and is
        // still part-way between the two tiles at x=82, so it fails this assertion by a wide margin.
        val frame =
            renderSyntheticFrame(
                widthPx = 100,
                heightPx = 64,
                background = SyntheticBackground.HorizontalGradient(levelAtLeft = 20.0, levelAtRight = 200.0, widthPx = 100),
                noise = SyntheticNoise.None,
            )

        val model = estimateTiledBackground(frame, tileSizePx = 64)
        val finalTileValue = model.tileLevel(1, 0)
        val centreOfFinalTile = model.tileCentreXPx(1)

        assertEquals(82.0, centreOfFinalTile)
        // At the final tile's real centre the model must BE that tile's measured value: there is nothing
        // beyond it to interpolate towards.
        assertEquals(
            finalTileValue,
            model.levelAt(sampleAt(centreOfFinalTile), 32),
            0.6,
            "the model must reach tile 1's value at x=82, not somewhere further right",
        )
        // And it must still be strictly below that value just inside the interpolated span, which is what
        // distinguishes "correctly reaching it at 82" from "flat across the whole remainder tile".
        assertTrue(
            model.levelAt(60, 32) < finalTileValue - 1.0,
            "x=60 lies between the two centres and must not already have reached tile 1's value",
        )
    }

    @Test
    fun `reaches the final tile value at the remainder tile's actual centre on a vertical gradient`() {
        val frame =
            renderSyntheticFrame(
                widthPx = 64,
                heightPx = 100,
                background = SyntheticBackground.VerticalGradient(levelAtTop = 20.0, levelAtBottom = 200.0, heightPx = 100),
                noise = SyntheticNoise.None,
            )

        val model = estimateTiledBackground(frame, tileSizePx = 64)

        assertEquals(2, model.tilesY)
        assertEquals(82.0, model.tileCentreYPx(1))
        assertEquals(
            model.tileLevel(0, 1),
            model.levelAt(32, sampleAt(model.tileCentreYPx(1))),
            0.6,
            "the model must reach the final row's value at y=82",
        )
    }

    @Test
    fun `does not flatten or shift the model across the remainder tile on a diagonal gradient`() {
        val frame =
            renderSyntheticFrame(
                widthPx = 100,
                heightPx = 100,
                background =
                    SyntheticBackground.LinearGradient(
                        levelAtOrigin = 20.0,
                        levelAtOpposite = 220.0,
                        widthPx = 100,
                        heightPx = 100,
                    ),
                noise = SyntheticNoise.None,
            )

        val model = estimateTiledBackground(frame, tileSizePx = 64)

        // Between the two measured centres the model must reproduce the injected ramp, because the ramp
        // is linear and a bilinear interpolation between correctly-placed nodes is exact on a linear
        // field. This is the direct statement of "no spatial shift": stretching the span from 50 px
        // (32 -> 82) to the nominal 64 px (32 -> 96) leaves the model lagging the true level by tens of
        // luma at the far end, which the tolerance below cannot absorb.
        //
        // Outside the outermost centres the model is deliberately flat — that is the documented clamp,
        // not a defect — so the comparison runs between the centres only.
        val firstCentre = sampleAt(model.tileCentreXPx(0))
        val lastCentre = sampleAt(model.tileCentreXPx(1))
        var worstError = 0.0
        var previous = model.levelAt(0, 0)
        for (i in firstCentre..lastCentre) {
            val trueLevel = 20.0 + (220.0 - 20.0) * ((i / 100.0 + i / 100.0) / 2.0)
            worstError = maxOf(worstError, abs(model.levelAt(i, i) - trueLevel))
        }
        assertTrue(worstError < 2.0, "the model departed from the injected ramp by $worstError luma")

        for (i in 1 until 100) {
            val current = model.levelAt(i, i)
            assertTrue(current >= previous - 0.51, "the model dipped at ($i, $i): $previous -> $current")
            previous = current
        }
    }

    @Test
    fun `degrades to a single global tile for a frame smaller than one tile`() {
        val frame =
            renderSyntheticFrame(
                widthPx = 16,
                heightPx = 16,
                background = SyntheticBackground.Uniform(90.0),
                noise = SyntheticNoise.None,
            )

        val model = estimateTiledBackground(frame, tileSizePx = 64)

        assertEquals(1, model.tilesX)
        assertEquals(1, model.tilesY)
        assertEquals(90.0, model.levelAt(0, 0))
        assertEquals(90.0, model.levelAt(15, 15))
    }

    @Test
    fun `tracks the injected noise across the range of skies, over a gradient`() {
        // Over a gradient the interpolated level sweeps ~10 luma across every tile, so a tile's residuals
        // are a genuinely continuous sample and the quartile resolves between luma levels.
        //
        // What it must converge on is not the injected sigma but the injected sigma *as the sensor
        // recorded it*: rounding a continuous value onto the 8-bit grid adds an independent uniform error
        // of variance 1/12, so the truth here is sqrt(sigma^2 + 1/12). Comparing against the bare injected
        // sigma instead would demand the estimator report less noise than the frame actually contains.
        for (injected in listOf(0.5, 1.0, 1.5, 2.5, 4.0, 6.0)) {
            val expected = sqrt(injected * injected + QUANTISATION_VARIANCE)
            val measured = interiorTileSigmas(injected)

            val mean = measured.average()
            assertTrue(
                abs(mean - expected) < 0.05,
                "injected $injected should read as $expected across the frame; the mean tile was $mean",
            )
            // And no single tile may hide behind that mean. 4096 samples put the quartile's own sampling
            // error near 5%; 15% leaves room for it without leaving room for a tile that missed the level.
            val worst = measured.maxBy { abs(it - expected) }
            assertTrue(
                abs(worst - expected) < 0.15 * expected,
                "injected $injected had a tile reading $worst, too far from $expected",
            )
        }
    }

    @Test
    fun `reports the sensor's own rounding as the noise floor of a noiseless gradient`() {
        // The zero-noise end of the sweep above, which behaves differently enough to state on its own. A
        // ramp rendered onto an 8-bit grid is not noiseless: each pixel carries the rounding error, which
        // is uniform on [-0.5, 0.5) rather than normal. The estimator converts a quartile assuming a
        // normal, and a uniform's lower quartile sits at 0.25 of its width, so the floor it reports is
        // 0.25 / 0.6745 = 0.371 luma rather than the 0.289 that distribution's standard deviation would
        // be. This is the smallest spread a real gradient frame can report, and it is a sensor property.
        val measured = interiorTileSigmas(injected = 0.0)

        assertTrue(
            measured.all { abs(it - 0.371) < 0.01 },
            "a noiseless ramp must report only the 8-bit rounding floor; got ${measured.distinct()}",
        )
    }

    @Test
    fun `quantises the spread to whole luma steps on a flat sky, and under-reports the faintest noise`() {
        // The other side of the same mechanism, and the estimator's real weakness. With no gradient the
        // interpolated level is *constant* across a tile, so every residual is an integer minus that one
        // constant and the quartile can only land on one of them. `sigma` is then pinned to multiples of
        // 1 / 0.6745 = 1.48 luma, exactly as it was before the spread moved onto the residual.
        //
        // Below about one luma of noise that rounds to zero and the estimator reports no spread at all.
        // The threshold does not collapse with it: StarDetectorConfig.minThresholdAboveBackground is the
        // floor that covers this case, and this test is here to keep the size of the gap visible.
        for (injected in listOf(0.0, 0.5)) {
            assertTrue(
                flatTileSigmas(injected).all { it == 0.0 },
                "noise of $injected luma is under one quantisation step and must read as no spread",
            )
        }

        for (injected in listOf(1.0, 1.5, 2.5, 4.0, 6.0)) {
            val measured = flatTileSigmas(injected)
            measured.forEach { sigma ->
                val steps = sigma * NORMAL_QUARTILE_SIGMAS
                assertEquals(
                    steps.roundToInt().toDouble(),
                    steps,
                    1.0e-9,
                    "a flat sky's spread must be a whole number of luma steps; $sigma is not",
                )
            }
            // Which leaves it within half a step of the truth once the noise is worth a step at all.
            assertTrue(
                measured.all { abs(it - injected) < 0.75 },
                "injected $injected read as ${measured.distinct()}, more than half a step away",
            )
        }
    }

    @Test
    fun `measures the sky's own noise over a gradient, not the gradient`() {
        // The gradient ramps ~10 luma across every tile in x and ~13 in y. Measured over a tile's raw
        // pixels that ramp is indistinguishable from noise and dominates it; measured on the residual it
        // is gone, because a bilinear interpolation between correctly-placed centres is exact on a linear
        // field.
        val frame = gradientFrame(noiseSigma = 2.5, seed = 5L)

        val fixed = estimateTiledBackground(frame)
        val raw = estimateTiledBackgroundOverRawPixels(frame)

        val interiorX = 320
        val interiorY = 240
        assertTrue(
            abs(fixed.sigmaAt(interiorX, interiorY) - 2.5) < 0.5,
            "the residual spread must report the injected 2.5; was ${fixed.sigmaAt(interiorX, interiorY)}",
        )
        assertTrue(
            raw.sigmaAt(interiorX, interiorY) > 2.0 * fixed.sigmaAt(interiorX, interiorY),
            "the raw-pixel spread must be the inflated one this test exists to distinguish from; " +
                "raw=${raw.sigmaAt(interiorX, interiorY)} fixed=${fixed.sigmaAt(interiorX, interiorY)}",
        )
        // The level is measured over the raw pixels either way and must be untouched by the change.
        for (tileY in 0 until fixed.tilesY) {
            for (tileX in 0 until fixed.tilesX) {
                assertEquals(raw.tileLevel(tileX, tileY), fixed.tileLevel(tileX, tileY), "tile ($tileX, $tileY)")
            }
        }
    }

    @Test
    fun `recovers faint stars over a gradient that the raw-pixel spread hid`() {
        // Stars at ~22 luma above their local sky: comfortably over the ~10 luma threshold the sky's real
        // 2.5-luma noise justifies, and comfortably under the ~32 the ramp-inflated spread demanded. They
        // are placed inside the outermost tile centres, where the interpolated model tracks the ramp; the
        // margin outside those centres is clamped flat by design and is not what this test is about.
        val faintStars =
            listOf(
                SyntheticStar(xPx = 128.5, yPx = 112.5, peakAboveBackground = 22.0),
                SyntheticStar(xPx = 256.3, yPx = 176.7, peakAboveBackground = 22.0),
                SyntheticStar(xPx = 384.6, yPx = 240.2, peakAboveBackground = 22.0),
                SyntheticStar(xPx = 448.1, yPx = 304.9, peakAboveBackground = 22.0),
                SyntheticStar(xPx = 192.8, yPx = 336.4, peakAboveBackground = 22.0),
                SyntheticStar(xPx = 512.2, yPx = 144.6, peakAboveBackground = 22.0),
            )
        val frame = gradientFrame(noiseSigma = 2.5, seed = 5L, stars = faintStars)
        val truth = faintStars.toPredictedPoints()

        val fixed = evaluateDetections(detectStars(frame), truth, tolerancePx = 2.0)
        val raw =
            evaluateDetections(
                detectStars(frame, estimateTiledBackgroundOverRawPixels(frame)),
                truth,
                tolerancePx = 2.0,
            )

        assertEquals(faintStars.size, fixed.matchedCount, "every faint star must be recovered: $fixed")
        assertEquals(0, fixed.falsePositiveCount, "and nothing else may be reported: $fixed")
        // The discriminating half: on the pre-fix estimator these same pixels yield nothing at all, so the
        // test cannot pass by accident on a build that has not had the fix applied.
        assertEquals(0, raw.matchedCount, "the raw-pixel spread must still hide them, or this proves nothing")
    }

    @Test
    fun `adds no detections to a starless gradient`() {
        // The other side of the same change: a lower threshold must buy real stars, not noise. Ten
        // independent realisations of a starless gradient sky, which is where the fix cuts the threshold
        // hardest and so where a threshold cut too far would show up first.
        val detections =
            (1..10).sumOf { seed ->
                detectStars(gradientFrame(noiseSigma = 2.5, seed = seed.toLong())).size
            }

        assertTrue(detections <= 2, "10 starless gradient frames must stay near zero detections; got $detections")
    }

    @Test
    fun `leaves a flat sky untouched`() {
        // With no gradient there is nothing to subtract, so the fix must not move anything that matters.
        val stars =
            listOf(
                SyntheticStar(xPx = 100.5, yPx = 80.5, peakAboveBackground = 140.0),
                SyntheticStar(xPx = 213.25, yPx = 141.75, peakAboveBackground = 90.0),
                SyntheticStar(xPx = 330.0, yPx = 96.0, peakAboveBackground = 180.0),
            )
        val frame =
            renderSyntheticFrame(
                widthPx = 384,
                heightPx = 256,
                background = SyntheticBackground.Uniform(60.0),
                stars = stars,
                noise = SyntheticNoise.Gaussian(sigma = 3.0),
                seed = 31L,
            )

        val fixed = estimateTiledBackground(frame)
        val raw = estimateTiledBackgroundOverRawPixels(frame)

        for (tileY in 0 until fixed.tilesY) {
            for (tileX in 0 until fixed.tilesX) {
                assertEquals(raw.tileLevel(tileX, tileY), fixed.tileLevel(tileX, tileY), "tile ($tileX, $tileY)")
                // Both estimators are reading the same flat sky, so they must agree on its noise to within
                // the luma bin the raw one is quantised to.
                assertEquals(
                    raw.tileSigma(tileX, tileY),
                    fixed.tileSigma(tileX, tileY),
                    1.5,
                    "tile ($tileX, $tileY) spread moved on a flat sky",
                )
            }
        }
        val report = evaluateDetections(detectStars(frame, fixed), stars.toPredictedPoints(), tolerancePx = 2.0)
        assertEquals(stars.size, report.matchedCount, "the same stars must still be found on a flat sky")
        assertEquals(0, report.falsePositiveCount, "and no others: $report")
    }

    /**
     * Every interior tile's spread over several noise realisations of a gradient sky. The outermost ring
     * is left out: outside the outermost tile centres the model clamps flat by design, so those tiles
     * carry a slice of the ramp in their residuals and are measuring something else.
     */
    private fun interiorTileSigmas(injected: Double): List<Double> =
        (1..SWEEP_REALISATIONS).flatMap { seed ->
            val model = estimateTiledBackground(gradientFrame(noiseSigma = injected, seed = seed.toLong()))
            (1 until model.tilesY - 1).flatMap { tileY ->
                (1 until model.tilesX - 1).map { model.tileSigma(it, tileY) }
            }
        }

    /** The same sweep over a sky with no gradient at all, where every tile is interior in the sense above. */
    private fun flatTileSigmas(injected: Double): List<Double> =
        (1..SWEEP_REALISATIONS).flatMap { seed ->
            val frame =
                renderSyntheticFrame(
                    widthPx = 256,
                    heightPx = 256,
                    background = SyntheticBackground.Uniform(120.0),
                    noise = noiseOf(injected),
                    seed = seed.toLong(),
                )
            val model = estimateTiledBackground(frame, tileSizePx = 64)
            (0 until model.tilesY).flatMap { tileY -> (0 until model.tilesX).map { model.tileSigma(it, tileY) } }
        }

    private fun gradientFrame(
        noiseSigma: Double,
        seed: Long,
        stars: List<SyntheticStar> = emptyList(),
    ): LumaFrame =
        renderSyntheticFrame(
            widthPx = 640,
            heightPx = 480,
            rowStridePx = 704,
            background =
                SyntheticBackground.LinearGradient(
                    levelAtOrigin = 20.0,
                    levelAtOpposite = 220.0,
                    widthPx = 640,
                    heightPx = 480,
                ),
            stars = stars,
            noise = noiseOf(noiseSigma),
            seed = seed,
        )

    /**
     * The raster sample whose centre is nearest continuous coordinate [coordinatePx]. A tile centre is a
     * continuous coordinate and can land on a sample boundary, so a test that wants to read the model
     * "at" a centre has to name a sample; sample `[x]` is centred at `x + 0.5`, so this inverts that.
     */
    private fun sampleAt(coordinatePx: Double): Int = (coordinatePx - 0.5).roundToInt()
}

/** No noise at all is its own case in the renderer, not a Gaussian of zero width. */
private fun noiseOf(sigma: Double): SyntheticNoise =
    if (sigma == 0.0) SyntheticNoise.None else SyntheticNoise.Gaussian(sigma = sigma)

/** How many independent noise realisations each point of the sweep averages over. */
private const val SWEEP_REALISATIONS = 5

/** Rounding a continuous value onto the 1-luma grid adds an independent uniform error of this variance. */
private const val QUANTISATION_VARIANCE = 1.0 / 12.0

/** The same constant [estimateTiledBackground] converts a quartile into a sigma with. */
private const val NORMAL_QUARTILE_SIGMAS = 0.6744897501960817

/**
 * The **pre-fix** background estimator, kept here as the reference the gradient tests measure against:
 * identical levels and identical tile centres, with the spread taken over each tile's raw pixels the way
 * [estimateTiledBackground] used to take it.
 *
 * It exists so "the fix recovers stars the old path lost" is asserted against the old path rather than
 * against a number someone once observed. A test written only against the current implementation would
 * pass on a build where the fix had been reverted; this one cannot.
 */
private fun estimateTiledBackgroundOverRawPixels(
    frame: LumaFrame,
    tileSizePx: Int = StarDetectorDefaults.BACKGROUND_TILE_SIZE_PX,
): TiledBackground {
    val bandsX = rawTileBands(frame.widthPx, tileSizePx)
    val bandsY = rawTileBands(frame.heightPx, tileSizePx)
    val tilesX = bandsX.size
    val tilesY = bandsY.size
    val levels = DoubleArray(tilesX * tilesY)
    val sigmas = DoubleArray(tilesX * tilesY)
    val histogram = IntArray(256)

    for (tileY in 0 until tilesY) {
        for (tileX in 0 until tilesX) {
            histogram.fill(0)
            var sampleCount = 0
            for (y in bandsY[tileY]) {
                for (x in bandsX[tileX]) {
                    histogram[frame.lumaAt(x, y)] += 1
                    sampleCount += 1
                }
            }
            val median = histogram.rawQuantile(sampleCount, 0.5)
            val lowerQuartile = histogram.rawQuantile(sampleCount, 0.25)
            levels[tileY * tilesX + tileX] = median
            sigmas[tileY * tilesX + tileX] = ((median - lowerQuartile) / NORMAL_QUARTILE_SIGMAS).coerceAtLeast(0.0)
        }
    }
    return TiledBackground(
        nominalTileSizePx = tileSizePx,
        tilesX = tilesX,
        tilesY = tilesY,
        centresXPx = DoubleArray(tilesX) { (bandsX[it].first + bandsX[it].last + 1) / 2.0 },
        centresYPx = DoubleArray(tilesY) { (bandsY[it].first + bandsY[it].last + 1) / 2.0 },
        levels = levels,
        sigmas = sigmas,
    )
}

private fun rawTileBands(
    lengthPx: Int,
    tileSizePx: Int,
): List<IntRange> {
    val count = ((lengthPx + tileSizePx - 1) / tileSizePx).coerceAtLeast(1)
    return (0 until count).map { index ->
        val start = index * tileSizePx
        val end = if (index == count - 1) lengthPx else ((index + 1) * tileSizePx).coerceAtMost(lengthPx)
        start until end
    }
}

private fun IntArray.rawQuantile(
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
