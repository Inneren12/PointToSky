package dev.pointtosky.core.astro.projection.camera.match

import dev.pointtosky.core.astro.projection.camera.detect.DetectedSource
import dev.pointtosky.core.astro.projection.camera.detect.SyntheticBackground
import dev.pointtosky.core.astro.projection.camera.detect.SyntheticNoise
import dev.pointtosky.core.astro.projection.camera.detect.SyntheticStar
import dev.pointtosky.core.astro.projection.camera.detect.detectStars
import dev.pointtosky.core.astro.projection.camera.detect.renderSyntheticFrame
import dev.pointtosky.core.astro.projection.camera.prediction.analysisBufferIntrinsics
import dev.pointtosky.core.astro.projection.camera.prediction.buildTestGeometry
import dev.pointtosky.core.astro.projection.camera.prediction.star
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The no-magnitude guard for [StarMatcherInput].
 *
 * `StarDetector.kt` states that [DetectedSource.brightness] must never be converted to a magnitude, and
 * [StarMatcherInput]'s KDoc repeats the prohibition for the matcher. A prohibition nobody can check is
 * a comment, so this file measures the underlying fact instead of restating it: two sources with
 * **identical total flux** and different profile widths come back with materially different
 * `brightness`, because the detection threshold truncates a broad profile's wings harder than a narrow
 * one's. Any monotone brightness→magnitude map would therefore assign two photometrically identical
 * stars magnitudes that differ by more than most real variability.
 */
class MatcherInputBrightnessContractTest {
    @Test
    fun `equal total flux does not measure equal, so brightness cannot be a magnitude`() {
        // Total flux of the rendered continuous Gaussian is peak * 2*pi*sigma^2. These two are chosen so
        // that product is the same for both, with the widths a factor of ~3.7 apart — a seeing
        // difference, not an exotic one.
        val narrow = SyntheticStar(xPx = 160.4, yPx = 240.6, peakAboveBackground = 200.0, fwhmPx = 2.5)
        val broad = SyntheticStar(xPx = 480.3, yPx = 240.2, peakAboveBackground = 15.0, fwhmPx = 9.1287)
        assertEquals(narrow.totalFlux(), broad.totalFlux(), 0.01 * narrow.totalFlux(), "the fixture's premise")

        val frame =
            renderSyntheticFrame(
                widthPx = 640,
                heightPx = 480,
                background = SyntheticBackground.Uniform(20.0),
                stars = listOf(narrow, broad),
                noise = SyntheticNoise.Gaussian(sigma = 2.0),
                seed = 161803L,
            )
        val detections = detectStars(frame)
        val narrowDetection = detections.nearest(narrow)
        val broadDetection = detections.nearest(broad)
        // Both sources must genuinely be recovered — the comparison is meaningless if one "detection"
        // is a noise blob standing in for a star the detector missed.
        assertTrue(narrowDetection.distanceTo(narrow) < 2.0, "the narrow source was not recovered")
        assertTrue(broadDetection.distanceTo(broad) < 2.0, "the broad source was not recovered")

        // Not a rounding difference: the narrow source measures far brighter despite carrying exactly
        // the same flux, purely because less of it fell below the threshold.
        val ratio = narrowDetection.brightness / broadDetection.brightness
        assertTrue(ratio > 1.3, "equal-flux sources should measure unequal; ratio was $ratio")

        // Expressed the way the mistake would actually be made: convert both to a "magnitude" with the
        // usual -2.5*log10(flux) and read off how far apart two identical stars land.
        val impliedMagnitudeGap = 2.5 * log10(ratio)
        assertTrue(
            impliedMagnitudeGap > 0.3,
            "a brightness-derived magnitude would misplace identical stars by $impliedMagnitudeGap mag",
        )
    }

    @Test
    fun `a detection carries no magnitude to be mistaken for one`() {
        val fieldNames = DetectedSource::class.java.declaredFields.map { it.name.lowercase() }

        assertTrue(
            fieldNames.none { it.contains("magnitude") || it.contains("flux") },
            "DetectedSource must not gain a magnitude-shaped field; has $fieldNames",
        )
        assertTrue("brightness" in fieldNames, "the relative brightness field is expected to stay named brightness")
    }

    @Test
    fun `the only magnitudes in a matcher input come from the catalog side`() {
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 640,
                bufferHeightPx = 480,
                viewportWidthPx = 640,
                viewportHeightPx = 480,
                intrinsicsResolution = analysisBufferIntrinsics(referenceWidthPx = 640, referenceHeightPx = 480),
            )
        val frame =
            renderSyntheticFrame(
                widthPx = 640,
                heightPx = 480,
                background = SyntheticBackground.Uniform(20.0),
                stars = listOf(SyntheticStar(xPx = 300.4, yPx = 220.6, peakAboveBackground = 120.0)),
                noise = SyntheticNoise.Gaussian(sigma = 2.0),
                seed = 271828L,
            )

        val input =
            StarMatcherInput.of(
                detections = detectStars(frame),
                candidates = listOf(star(catalogIndex = 0, magnitude = 2.5), star(catalogIndex = 1, magnitude = 4.75)),
                scale = AnalysisBufferScale.forGeometry(geometry),
            )

        assertEquals(listOf(2.5, 4.75), input.candidates.mapNotNull { it.magnitude })
        assertTrue(input.detectionCount > 0, "the frame must contain a detection for this to be a real check")
    }

    /** Analytic total flux of the rendered continuous Gaussian: `peak * 2*pi*sigma^2`. */
    private fun SyntheticStar.totalFlux(): Double = peakAboveBackground * 2.0 * PI * sigmaPx * sigmaPx

    private fun List<DetectedSource>.nearest(target: SyntheticStar): DetectedSource = minBy { it.distanceTo(target) }

    private fun DetectedSource.distanceTo(target: SyntheticStar): Double = hypot(xPx - target.xPx, yPx - target.yPx)
}
