package dev.pointtosky.core.astro.projection.camera.detect

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The renderer is the ground truth every detector test is scored against, so it is checked on its own
 * terms first: a fixture that quietly rendered a star half a pixel off would make the detector look
 * wrong (or, worse, look right for the wrong reason).
 */
class SyntheticFrameRendererTest {
    @Test
    fun `renders a star whose own flux centroid sits at the requested sub-pixel position`() {
        val star = SyntheticStar(xPx = 32.25, yPx = 24.75, peakAboveBackground = 150.0)
        val frame =
            renderSyntheticFrame(
                widthPx = 64,
                heightPx = 48,
                background = SyntheticBackground.Uniform(20.0),
                stars = listOf(star),
                noise = SyntheticNoise.None,
            )

        var weight = 0.0
        var weightedX = 0.0
        var weightedY = 0.0
        for (y in 0 until 48) {
            for (x in 0 until 64) {
                val above = (frame.lumaAt(x, y) - 20.0).coerceAtLeast(0.0)
                weight += above
                weightedX += above * (x + 0.5)
                weightedY += above * (y + 0.5)
            }
        }

        assertTrue(abs(weightedX / weight - star.xPx) < 0.02, "rendered x centroid was ${weightedX / weight}")
        assertTrue(abs(weightedY / weight - star.yPx) < 0.02, "rendered y centroid was ${weightedY / weight}")
    }

    @Test
    fun `clips a star brighter than the 8-bit range`() {
        val frame =
            renderSyntheticFrame(
                widthPx = 32,
                heightPx = 32,
                background = SyntheticBackground.Uniform(20.0),
                stars = listOf(SyntheticStar(xPx = 16.5, yPx = 16.5, peakAboveBackground = 900.0)),
                noise = SyntheticNoise.None,
            )

        assertEquals(255, frame.lumaAt(16, 16))
    }

    @Test
    fun `fills row padding with a saturated value`() {
        val frame =
            renderSyntheticFrame(
                widthPx = 8,
                heightPx = 4,
                rowStridePx = 12,
                background = SyntheticBackground.Uniform(20.0),
                noise = SyntheticNoise.None,
            )

        // Not reachable through lumaAt by construction — that is the point. Reading it back through a
        // frame that wrongly assumed tight packing is what the detector's stride test exercises.
        val packed = LumaFrame(data = ByteArray(12 * 4) { 0 }, widthPx = 12, heightPx = 4, rowStridePx = 12)
        assertEquals(0, packed.lumaAt(11, 0))
        assertEquals(20, frame.lumaAt(7, 0))
    }

    @Test
    fun `ramps a linear gradient across the frame`() {
        val frame =
            renderSyntheticFrame(
                widthPx = 64,
                heightPx = 64,
                background =
                    SyntheticBackground.LinearGradient(
                        levelAtOrigin = 10.0,
                        levelAtOpposite = 200.0,
                        widthPx = 64,
                        heightPx = 64,
                    ),
                noise = SyntheticNoise.None,
            )

        assertEquals(10, frame.lumaAt(0, 0))
        assertTrue(frame.lumaAt(63, 63) > 180, "the bright corner was ${frame.lumaAt(63, 63)}")
        assertTrue(frame.lumaAt(32, 32) in 95..110, "the midpoint was ${frame.lumaAt(32, 32)}")
    }

    @Test
    fun `is reproducible for a given seed and different across seeds`() {
        fun render(seed: Long): List<Int> {
            val frame =
                renderSyntheticFrame(
                    widthPx = 32,
                    heightPx = 32,
                    noise = SyntheticNoise.Gaussian(sigma = 5.0),
                    seed = seed,
                )
            return (0 until 32).flatMap { y -> (0 until 32).map { x -> frame.lumaAt(x, y) } }
        }

        assertEquals(render(seed = 1L), render(seed = 1L), "a seeded render must be reproducible")
        assertTrue(render(seed = 1L) != render(seed = 2L), "different seeds must give different noise")
    }

    @Test
    fun `places a hot pixel exactly where asked, honouring stride`() {
        val frame =
            renderSyntheticFrame(
                widthPx = 16,
                heightPx = 16,
                rowStridePx = 24,
                background = SyntheticBackground.Uniform(20.0),
                hotPixels = listOf(SyntheticHotPixel(x = 5, y = 7)),
                noise = SyntheticNoise.None,
            )

        assertEquals(HOT_PIXEL_LUMA, frame.lumaAt(5, 7))
        assertEquals(20, frame.lumaAt(4, 7))
        assertEquals(20, frame.lumaAt(6, 7))
    }
}
