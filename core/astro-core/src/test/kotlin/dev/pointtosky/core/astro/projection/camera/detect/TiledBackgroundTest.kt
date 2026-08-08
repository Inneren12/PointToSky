package dev.pointtosky.core.astro.projection.camera.detect

import kotlin.math.abs
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
}
