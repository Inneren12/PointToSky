package dev.pointtosky.core.astro.projection.camera.detect

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SKY-2 detector tests. Every frame here is rendered by [renderSyntheticFrame] with the star positions
 * chosen by the test, so a centroid is scored against the number it was drawn at rather than against
 * another estimate of it.
 */
class StarDetectorTest {
    // A 640x480 analysis buffer with 64 bytes of row padding, matching what a real capture hands over:
    // every test therefore exercises the stride path rather than the tightly-packed special case.
    private val widthPx = 640
    private val heightPx = 480
    private val rowStridePx = 704

    private val cleanStars =
        listOf(
            SyntheticStar(xPx = 100.5, yPx = 80.5, peakAboveBackground = 140.0),
            SyntheticStar(xPx = 213.25, yPx = 141.75, peakAboveBackground = 90.0),
            SyntheticStar(xPx = 330.0, yPx = 96.0, peakAboveBackground = 180.0),
            SyntheticStar(xPx = 455.8, yPx = 210.2, peakAboveBackground = 110.0, fwhmPx = 4.0),
            SyntheticStar(xPx = 520.4, yPx = 330.6, peakAboveBackground = 70.0),
            SyntheticStar(xPx = 150.9, yPx = 300.1, peakAboveBackground = 160.0),
            SyntheticStar(xPx = 280.3, yPx = 400.7, peakAboveBackground = 120.0),
            SyntheticStar(xPx = 400.0, yPx = 350.5, peakAboveBackground = 100.0, fwhmPx = 2.5),
        )

    private fun cleanFrame(
        stride: Int = rowStridePx,
        seed: Long = 4242L,
    ): LumaFrame =
        renderSyntheticFrame(
            widthPx = widthPx,
            heightPx = heightPx,
            rowStridePx = stride,
            background = SyntheticBackground.Uniform(DEFAULT_BACKGROUND_LUMA),
            stars = cleanStars,
            noise = SyntheticNoise.Gaussian(sigma = 2.0),
            seed = seed,
        )

    @Test
    fun `recovers injected stars at sub-pixel accuracy`() {
        val detections = detectStars(cleanFrame())
        val report = evaluateDetections(detections, cleanStars.toPredictedPoints(), tolerancePx = 2.0)

        assertEquals(cleanStars.size, report.matchedCount, "every injected star must be recovered")
        val rms = assertNotNull(report.centroidResidualRmsPx)
        assertTrue(rms < 0.2, "centroid residual RMS must be well sub-pixel; was $rms px")
    }

    @Test
    fun `detects exactly the injected count with no spurious sources`() {
        val detections = detectStars(cleanFrame())

        assertEquals(cleanStars.size, detections.size, "N injected must give N detected: $detections")
    }

    @Test
    fun `honours row stride`() {
        // The renderer fills the padding with saturated bytes, so a detector reading the buffer as
        // tightly packed would find a bright column that no star was drawn at.
        val padded = detectStars(cleanFrame(stride = rowStridePx))
        val packed = detectStars(cleanFrame(stride = widthPx))

        assertContentEquals(packed, padded, "row padding must not change what is detected")
    }

    @Test
    fun `reports brightness monotonic with injected flux, not with injected peak`() {
        val detections = detectStars(cleanFrame())
        val report = evaluateDetections(detections, cleanStars.toPredictedPoints(), tolerancePx = 2.0)
        assertEquals(cleanStars.size, report.matchedCount)

        // DetectedSource.brightness is integrated flux, and a Gaussian's total flux is proportional to
        // peak * sigma^2 — so a broad, lower-peaked star legitimately outranks a narrow, higher-peaked
        // one. This fixture contains exactly that pair (the 110-peak FWHM 4.0 star against the 180-peak
        // FWHM 3.0 one), so ranking by peak would fail here.
        //
        // The ranking is asserted only for pairs whose injected fluxes differ by more than
        // FLUX_ORDERING_MARGIN, because what is measured is flux above the *threshold*, not total flux: a
        // narrow profile keeps a larger share of itself above the cut than a broad one of the same total,
        // so two stars within a percent of each other can rank either way. That is a property of the
        // measure, not a defect, and pretending otherwise would make this test a tolerance lottery.
        for (a in report.matches) {
            for (b in report.matches) {
                val fluxA = cleanStars[a.predictedIndex].injectedFlux()
                val fluxB = cleanStars[b.predictedIndex].injectedFlux()
                if (fluxA <= fluxB * (1.0 + FLUX_ORDERING_MARGIN)) continue
                assertTrue(
                    detections[a.detectedIndex].brightness > detections[b.detectedIndex].brightness,
                    "star ${a.predictedIndex} (injected flux $fluxA) must measure brighter than " +
                        "star ${b.predictedIndex} (injected flux $fluxB)",
                )
            }
        }
    }

    @Test
    fun `finds nothing in a frame of pure background and noise`() {
        val frame =
            renderSyntheticFrame(
                widthPx = widthPx,
                heightPx = heightPx,
                rowStridePx = rowStridePx,
                background = SyntheticBackground.Uniform(DEFAULT_BACKGROUND_LUMA),
                noise = SyntheticNoise.Gaussian(sigma = 3.0),
                seed = 90210L,
            )

        assertTrue(detectStars(frame).isEmpty(), "a starless frame must produce no detections")
    }

    @Test
    fun `keeps the false-positive rate bounded across independent noise realisations`() {
        // One seed proves nothing about a rate. Ten independent frames of the same starless sky bound it:
        // at 4 sigma with a 3-pixel minimum, a noise clump large enough to survive is vanishingly rare.
        val falsePositives =
            (1..10).sumOf { seed ->
                val frame =
                    renderSyntheticFrame(
                        widthPx = widthPx,
                        heightPx = heightPx,
                        rowStridePx = rowStridePx,
                        background = SyntheticBackground.Uniform(DEFAULT_BACKGROUND_LUMA),
                        noise = SyntheticNoise.Gaussian(sigma = 4.0),
                        seed = seed.toLong(),
                    )
                detectStars(frame).size
            }

        assertTrue(
            falsePositives <= 2,
            "10 starless 640x480 frames must yield at most a couple of false positives; got $falsePositives",
        )
    }

    @Test
    fun `detects stars across a strong light-pollution gradient`() {
        val gradientStars =
            listOf(
                // Over the dark corner.
                SyntheticStar(xPx = 60.5, yPx = 50.5, peakAboveBackground = 90.0),
                // Mid-ramp.
                SyntheticStar(xPx = 320.25, yPx = 240.75, peakAboveBackground = 90.0),
                // Over the bright corner, where a global threshold set for the dark end drowns.
                SyntheticStar(xPx = 580.6, yPx = 430.4, peakAboveBackground = 90.0),
            )
        val background =
            SyntheticBackground.LinearGradient(
                levelAtOrigin = 15.0,
                levelAtOpposite = 180.0,
                widthPx = widthPx,
                heightPx = heightPx,
            )
        val frame =
            renderSyntheticFrame(
                widthPx = widthPx,
                heightPx = heightPx,
                rowStridePx = rowStridePx,
                background = background,
                stars = gradientStars,
                noise = SyntheticNoise.Gaussian(sigma = 2.5),
                seed = 7L,
            )

        val model = estimateTiledBackground(frame)
        // The local path is what is being proved: the model must actually track the ramp, not flatten it.
        val darkTile = model.tileLevel(0, 0)
        val brightTile = model.tileLevel(model.tilesX - 1, model.tilesY - 1)
        assertTrue(
            brightTile - darkTile > 100.0,
            "the tiled model must follow the gradient (dark=$darkTile bright=$brightTile)",
        )

        val detections = detectStars(frame, model)
        val report = evaluateDetections(detections, gradientStars.toPredictedPoints(), tolerancePx = 2.0)
        assertEquals(gradientStars.size, report.matchedCount, "every star over the gradient must be found")
        assertEquals(0, report.falsePositiveCount, "the gradient itself must not be detected as sources")
    }

    @Test
    fun `rejects single-pixel hot pixels while keeping real stars`() {
        val stars =
            listOf(
                SyntheticStar(xPx = 200.5, yPx = 150.5, peakAboveBackground = 120.0),
                SyntheticStar(xPx = 450.25, yPx = 320.75, peakAboveBackground = 95.0),
            )
        val hotPixels =
            listOf(
                SyntheticHotPixel(x = 90, y = 90),
                SyntheticHotPixel(x = 300, y = 250),
                SyntheticHotPixel(x = 500, y = 100),
            )
        val frame =
            renderSyntheticFrame(
                widthPx = widthPx,
                heightPx = heightPx,
                rowStridePx = rowStridePx,
                stars = stars,
                hotPixels = hotPixels,
                noise = SyntheticNoise.Gaussian(sigma = 2.0),
                seed = 31337L,
            )

        val detections = detectStars(frame)

        assertEquals(stars.size, detections.size, "only the real stars survive the minimum-size filter")
        for (hotPixel in hotPixels) {
            val nearest = detections.minOf { hypot(it.xPx - (hotPixel.x + 0.5), it.yPx - (hotPixel.y + 0.5)) }
            assertTrue(nearest > 5.0, "no detection may sit on hot pixel (${hotPixel.x}, ${hotPixel.y})")
        }
    }

    @Test
    fun `flags a saturated star and leaves an unsaturated one unflagged`() {
        val saturated = SyntheticStar(xPx = 200.5, yPx = 200.5, peakAboveBackground = 600.0)
        val ordinary = SyntheticStar(xPx = 450.5, yPx = 300.5, peakAboveBackground = 90.0)
        val frame =
            renderSyntheticFrame(
                widthPx = widthPx,
                heightPx = heightPx,
                rowStridePx = rowStridePx,
                stars = listOf(saturated, ordinary),
                noise = SyntheticNoise.Gaussian(sigma = 2.0),
                seed = 11L,
            )

        val detections = detectStars(frame)
        val report = evaluateDetections(detections, listOf(saturated, ordinary).toPredictedPoints(), tolerancePx = 2.0)
        assertEquals(2, report.matchedCount)

        val saturatedDetection = detections[report.matches.single { it.predictedIndex == 0 }.detectedIndex]
        val ordinaryDetection = detections[report.matches.single { it.predictedIndex == 1 }.detectedIndex]
        assertTrue(saturatedDetection.saturated, "a clipped star must be flagged saturated")
        assertEquals(255, saturatedDetection.peakLuma)
        assertFalse(ordinaryDetection.saturated, "a star well below clipping must not be flagged")
        // A saturated source is still returned with a usable centroid — the flat top is symmetric, so
        // clipping costs flux, not position.
        assertTrue(
            hypot(saturatedDetection.xPx - saturated.xPx, saturatedDetection.yPx - saturated.yPx) < 0.5,
            "a saturated star's centroid must still be sub-pixel",
        )
    }

    @Test
    fun `flags a border source as near-edge and drops it only under the strict policy`() {
        // Centred two pixels from the left border, so the PSF is truncated by the frame.
        val edgeStar = SyntheticStar(xPx = 2.0, yPx = 240.5, peakAboveBackground = 150.0)
        val interiorStar = SyntheticStar(xPx = 320.5, yPx = 240.5, peakAboveBackground = 150.0)
        val frame =
            renderSyntheticFrame(
                widthPx = widthPx,
                heightPx = heightPx,
                rowStridePx = rowStridePx,
                stars = listOf(edgeStar, interiorStar),
                noise = SyntheticNoise.Gaussian(sigma = 2.0),
                seed = 5L,
            )

        val detections = detectStars(frame)
        assertEquals(2, detections.size, "the default policy keeps an edge source")
        val edgeDetection = detections.single { it.xPx < widthPx / 2.0 }
        val interiorDetection = detections.single { it.xPx > widthPx / 2.0 }
        assertTrue(edgeDetection.nearEdge, "a source touching the border must be flagged nearEdge")
        assertFalse(interiorDetection.nearEdge, "an interior source must not be flagged")

        val strict = detectStars(frame, StarDetectorConfig(rejectNearEdge = true))
        assertEquals(listOf(false), strict.map { it.nearEdge }, "the strict policy drops the edge source")
    }

    @Test
    fun `is deterministic across repeated runs and across identical frames`() {
        val first = detectStars(cleanFrame())
        val second = detectStars(cleanFrame())
        val sameFrameTwice = cleanFrame().let { detectStars(it) to detectStars(it) }

        assertContentEquals(first, second, "identical input must give an identical list")
        assertContentEquals(sameFrameTwice.first, sameFrameTwice.second, "repeated runs must not diverge")
    }

    @Test
    fun `orders detections brightest first`() {
        val detections = detectStars(cleanFrame())

        val brightnesses = detections.map { it.brightness }
        assertEquals(brightnesses.sortedDescending(), brightnesses, "output order must be brightest first")
    }

    @Test
    fun `rejects blobs larger than the maximum pixel count`() {
        // A wide, bright blob standing in for moon glow or a cloud edge: far too large to be a star.
        val blob = SyntheticStar(xPx = 320.5, yPx = 240.5, peakAboveBackground = 200.0, fwhmPx = 90.0)
        val star = SyntheticStar(xPx = 90.5, yPx = 90.5, peakAboveBackground = 120.0)
        val frame =
            renderSyntheticFrame(
                widthPx = widthPx,
                heightPx = heightPx,
                rowStridePx = rowStridePx,
                stars = listOf(blob, star),
                noise = SyntheticNoise.Gaussian(sigma = 2.0),
                seed = 77L,
            )

        val detections = detectStars(frame, StarDetectorConfig(maxPixelCount = 500))

        assertEquals(1, detections.size, "the oversized blob must be rejected: $detections")
        assertTrue(abs(detections.single().xPx - star.xPx) < 1.0)
    }

    @Test
    fun `finds a star in a frame with no noise at all`() {
        // sigma measures zero here, so only StarDetectorConfig.minThresholdAboveBackground keeps the
        // whole frame from reading as one source.
        val star = SyntheticStar(xPx = 64.5, yPx = 48.5, peakAboveBackground = 100.0)
        val frame =
            renderSyntheticFrame(
                widthPx = 128,
                heightPx = 96,
                background = SyntheticBackground.Uniform(20.0),
                stars = listOf(star),
                noise = SyntheticNoise.None,
            )

        val detections = detectStars(frame)

        assertEquals(1, detections.size, "a noiseless frame must yield exactly the injected star")
        assertTrue(hypot(detections.single().xPx - star.xPx, detections.single().yPx - star.yPx) < 0.1)
    }

    @Test
    fun `survives Poisson shot noise`() {
        val stars =
            listOf(
                SyntheticStar(xPx = 120.5, yPx = 100.5, peakAboveBackground = 120.0),
                SyntheticStar(xPx = 400.25, yPx = 300.75, peakAboveBackground = 140.0),
            )
        val frame =
            renderSyntheticFrame(
                widthPx = widthPx,
                heightPx = heightPx,
                rowStridePx = rowStridePx,
                background = SyntheticBackground.Uniform(30.0),
                stars = stars,
                noise = SyntheticNoise.Poisson,
                seed = 2026L,
            )

        val report = evaluateDetections(detectStars(frame), stars.toPredictedPoints(), tolerancePx = 2.0)

        assertEquals(stars.size, report.matchedCount, "shot noise must not lose a bright star")
        assertEquals(0, report.falsePositiveCount)
    }

    /** Total flux of the rendered Gaussian, up to a constant shared by every star: `peak * sigma^2`. */
    private fun SyntheticStar.injectedFlux(): Double = peakAboveBackground * sigmaPx * sigmaPx

    private companion object {
        /** Fractional flux difference above which the measured brightness ordering is required to hold. */
        const val FLUX_ORDERING_MARGIN = 0.15
    }
}
