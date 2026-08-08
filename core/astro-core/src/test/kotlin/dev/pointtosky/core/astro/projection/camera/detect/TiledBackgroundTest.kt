package dev.pointtosky.core.astro.projection.camera.detect

import kotlin.math.abs
import kotlin.math.roundToInt
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
        assertEquals(before.tileSigma(0, 0), after.tileSigma(0, 0), "a star must not inflate the tile spread")
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

    /**
     * The raster sample whose centre is nearest continuous coordinate [coordinatePx]. A tile centre is a
     * continuous coordinate and can land on a sample boundary, so a test that wants to read the model
     * "at" a centre has to name a sample; sample `[x]` is centred at `x + 0.5`, so this inverts that.
     */
    private fun sampleAt(coordinatePx: Double): Int = (coordinatePx - 0.5).roundToInt()
}
