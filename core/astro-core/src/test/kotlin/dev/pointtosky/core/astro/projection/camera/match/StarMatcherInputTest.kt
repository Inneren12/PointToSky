package dev.pointtosky.core.astro.projection.camera.match

import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.PixelPoint
import dev.pointtosky.core.astro.projection.camera.detect.DetectedSource
import dev.pointtosky.core.astro.projection.camera.detect.PredictedPointPx
import dev.pointtosky.core.astro.projection.camera.detect.SyntheticBackground
import dev.pointtosky.core.astro.projection.camera.detect.SyntheticNoise
import dev.pointtosky.core.astro.projection.camera.detect.SyntheticStar
import dev.pointtosky.core.astro.projection.camera.detect.detectStars
import dev.pointtosky.core.astro.projection.camera.detect.evaluateDetections
import dev.pointtosky.core.astro.projection.camera.detect.renderSyntheticFrame
import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarProjection
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionBatchResult
import dev.pointtosky.core.astro.projection.camera.prediction.StarProjectionContext
import dev.pointtosky.core.astro.projection.camera.prediction.analysisBufferIntrinsics
import dev.pointtosky.core.astro.projection.camera.prediction.buildTestGeometry
import dev.pointtosky.core.astro.projection.camera.prediction.projectStars
import dev.pointtosky.core.astro.projection.camera.prediction.star
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * The assembled matcher input, checked for the two things a future matcher will silently depend on: the
 * pixel spaces line up with no transform between them, and detection identity survives being handed
 * through the DTO.
 *
 * The residual test is deliberately built the way `PixelConventionBridgeTest` is — a real
 * `projectStars` output, rendered into real pixels, detected back — rather than from hand-authored
 * coordinates, because coordinates authored under the same assumption as the code being tested prove
 * nothing about that assumption.
 */
class StarMatcherInputTest {
    private val bufferWidthPx = 640
    private val bufferHeightPx = 480

    @Test
    fun `a residual against a prior prediction is a plain subtraction`() {
        val geometry = geometry()
        val scale = AnalysisBufferScale.forGeometry(geometry)
        val (candidates, projections) = projectFixtureStars(geometry)
        val visible = wellSeparatedInBuffer(projections)
        assertTrue(visible.size >= 3, "the fixture must project at least 3 usable stars; got ${visible.size}")

        val frame =
            renderSyntheticFrame(
                widthPx = bufferWidthPx,
                heightPx = bufferHeightPx,
                rowStridePx = bufferWidthPx + 64,
                background = SyntheticBackground.Uniform(22.0),
                stars =
                    visible.map {
                        SyntheticStar(xPx = it.imagePoint!!.x, yPx = it.imagePoint!!.y, peakAboveBackground = 140.0)
                    },
                noise = SyntheticNoise.Gaussian(sigma = 2.0),
                seed = 314159L,
            )

        val input =
            StarMatcherInput.of(
                detections = detectStars(frame),
                candidates = candidates,
                scale = scale,
                priorProjections = projections,
            )

        val pairs = pairDetectionsToPredictions(input, visible)
        assertEquals(visible.size, pairs.size, "every projected star must be recovered")

        val offsetsX = pairs.map { (detection, prediction) -> detection.xPx - prediction.imagePoint!!.x }
        val offsetsY = pairs.map { (detection, prediction) -> detection.yPx - prediction.imagePoint!!.y }
        for (index in offsetsX.indices) {
            assertTrue(
                abs(offsetsX[index]) < PER_SOURCE_TOLERANCE_PX && abs(offsetsY[index]) < PER_SOURCE_TOLERANCE_PX,
                "pair $index came back offset by (${offsetsX[index]}, ${offsetsY[index]}) px",
            )
        }

        val meanX = offsetsX.average()
        val meanY = offsetsY.average()
        assertTrue(abs(meanX) < SYSTEMATIC_TOLERANCE_PX, "systematic X offset of $meanX px through the DTO")
        assertTrue(abs(meanY) < SYSTEMATIC_TOLERANCE_PX, "systematic Y offset of $meanY px through the DTO")
        // Without this the test would pass just as happily on a DTO that shifted every coordinate by
        // half a pixel, which is exactly the failure it exists to exclude.
        assertTrue(
            abs(meanX + HALF_PIXEL) > SENSITIVITY_MARGIN_PX && abs(meanY + HALF_PIXEL) > SENSITIVITY_MARGIN_PX,
            "this test could not distinguish a half-pixel shift (meanX=$meanX, meanY=$meanY)",
        )
    }

    @Test
    fun `the scale it carries is the one the predictions were projected with`() {
        val geometry = geometry()
        val (candidates, projections) = projectFixtureStars(geometry)

        val input =
            StarMatcherInput.of(
                detections = emptyList(),
                candidates = candidates,
                scale = AnalysisBufferScale.forGeometry(geometry),
                priorProjections = projections,
            )

        // An on-axis ray must land on the principal point the carrier reports; if the DTO's scale came
        // from a different frame or a different intrinsics value, this is where it would show.
        val onAxis = input.scale.pinhole.project(normalizedX = 0.0, normalizedY = 0.0)
        assertEquals(PixelPoint(input.scale.principalPointXPx, input.scale.principalPointYPx), onAxis)
        assertEquals(bufferWidthPx.toDouble(), input.scale.imageWidthPx)
        assertEquals(bufferHeightPx.toDouble(), input.scale.imageHeightPx)
    }

    @Test
    fun `detection identity is list position and survives the DTO unchanged`() {
        val geometry = geometry()
        val frame =
            renderSyntheticFrame(
                widthPx = bufferWidthPx,
                heightPx = bufferHeightPx,
                background = SyntheticBackground.Uniform(20.0),
                stars =
                    listOf(
                        SyntheticStar(xPx = 120.3, yPx = 200.7, peakAboveBackground = 90.0),
                        SyntheticStar(xPx = 400.8, yPx = 150.2, peakAboveBackground = 180.0),
                        SyntheticStar(xPx = 300.1, yPx = 380.6, peakAboveBackground = 130.0),
                    ),
                noise = SyntheticNoise.Gaussian(sigma = 2.0),
                seed = 2718L,
            )
        val detections = detectStars(frame)

        val input = StarMatcherInput.of(detections, emptyList(), AnalysisBufferScale.forGeometry(geometry))

        assertEquals(detections.size, input.detectionCount)
        for (id in 0 until input.detectionCount) {
            assertEquals(detections[id], input.detectionAt(id))
        }
        // Same pixels in, same order out — which is what makes the index usable as an identity at all.
        assertEquals(detections, detectStars(frame))
        // Brightest first: the id ordering the DTO inherits is the detector's documented total order.
        val brightnesses = detections.map { it.brightness }
        assertEquals(brightnesses.sortedDescending(), brightnesses)
    }

    @Test
    fun `duplicate candidate identities are rejected`() {
        val scale = AnalysisBufferScale.forGeometry(geometry())
        val duplicated =
            listOf(
                star(catalogIndex = 7, rightAscensionRad = 0.1, declinationRad = 0.2),
                star(catalogIndex = 7, rightAscensionRad = 0.3, declinationRad = 0.4),
            )

        assertFailsWith<IllegalArgumentException> { StarMatcherInput.of(emptyList(), duplicated, scale) }
    }

    @Test
    fun `a prediction about a star the matcher was not given is rejected`() {
        val geometry = geometry()
        val (candidates, projections) = projectFixtureStars(geometry)
        val scale = AnalysisBufferScale.forGeometry(geometry)

        assertFailsWith<IllegalArgumentException> {
            StarMatcherInput.of(
                detections = emptyList(),
                candidates = candidates.drop(1),
                scale = scale,
                priorProjections = projections,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            StarMatcherInput.of(
                detections = emptyList(),
                candidates = candidates,
                scale = scale,
                priorProjections = projections + projections.first(),
            )
        }
    }

    @Test
    fun `an empty prior is a valid, and the honest default, input`() {
        val geometry = geometry()
        val (candidates, _) = projectFixtureStars(geometry)

        val input = StarMatcherInput.of(emptyList(), candidates, AnalysisBufferScale.forGeometry(geometry))

        assertEquals(emptyList(), input.priorProjections)
        assertEquals(candidates.size, input.candidateCount)
        assertNotEquals(0, input.candidateCount)
    }

    @Test
    fun `mutating the caller's detection list afterwards cannot renumber a detection`() {
        val scale = AnalysisBufferScale.forGeometry(geometry())
        val first = detection(xPx = 10.0, yPx = 20.0, brightness = 900.0)
        val second = detection(xPx = 30.0, yPx = 40.0, brightness = 400.0)
        val source = mutableListOf(first, second)

        val input = StarMatcherInput.of(source, emptyList(), scale)

        // Identity here *is* the list index, so a caller reordering its own list after construction
        // would silently rename every detection the matcher had already referred to.
        source.reverse()
        source.add(detection(xPx = 50.0, yPx = 60.0, brightness = 100.0))
        source.clear()

        assertEquals(listOf(first, second), input.detections)
        assertEquals(2, input.detectionCount)
        assertEquals(first, input.detectionAt(0))
        assertEquals(second, input.detectionAt(1))
    }

    @Test
    fun `mutating the caller's candidate list afterwards cannot defeat the uniqueness check`() {
        val scale = AnalysisBufferScale.forGeometry(geometry())
        val alpha = star(catalogIndex = 3, rightAscensionRad = 0.1, declinationRad = 0.2)
        val beta = star(catalogIndex = 9, rightAscensionRad = 0.3, declinationRad = 0.4)
        val source = mutableListOf(alpha, beta)

        val input = StarMatcherInput.of(detections = emptyList(), candidates = source, scale = scale)

        // A duplicate catalogIndex inserted after `init` ran would make a matched pair ambiguous —
        // exactly the state construction rejects.
        source.add(star(catalogIndex = 3, rightAscensionRad = 1.1, declinationRad = 0.5))
        source.removeAt(0)

        assertEquals(listOf(alpha, beta), input.candidates)
        assertEquals(2, input.candidateCount)
        assertEquals(
            input.candidates.size,
            input.candidates.map { it.catalogIndex }.toSet().size,
            "catalogIndex uniqueness must survive external mutation",
        )
    }

    @Test
    fun `mutating the caller's prior list afterwards cannot orphan a prediction`() {
        val geometry = geometry()
        val (candidates, projections) = projectFixtureStars(geometry)
        val source = projections.toMutableList()

        val input =
            StarMatcherInput.of(
                detections = emptyList(),
                candidates = candidates,
                scale = AnalysisBufferScale.forGeometry(geometry),
                priorProjections = source,
            )

        source.clear()

        assertEquals(projections, input.priorProjections)
        assertTrue(
            input.priorProjections.all { it.catalogIndex in candidates.map { candidate -> candidate.catalogIndex } },
            "every prior must still name a candidate",
        )
    }

    @Test
    fun `the snapshots do not share backing storage with the caller's lists`() {
        val scale = AnalysisBufferScale.forGeometry(geometry())
        val detections = mutableListOf(detection(xPx = 1.0, yPx = 2.0, brightness = 3.0))
        val candidates = mutableListOf(star(catalogIndex = 0, rightAscensionRad = 0.5, declinationRad = 0.1))

        val input = StarMatcherInput.of(detections, candidates, scale)

        // Value-equal but never the same instance: an identity match would mean the stored list *is*
        // the caller's, and every assertion above would be one `add` away from being false.
        assertNotSame(detections, input.detections)
        assertNotSame(candidates, input.candidates)
        assertEquals(detections.toList(), input.detections)
        assertEquals(candidates.toList(), input.candidates)
    }

    /** A [DetectedSource] with plausible, unremarkable values — only position and brightness matter here. */
    private fun detection(
        xPx: Double,
        yPx: Double,
        brightness: Double,
    ): DetectedSource =
        DetectedSource(
            xPx = xPx,
            yPx = yPx,
            brightness = brightness,
            peakLuma = 200,
            localBackgroundLuma = 20.0,
            pixelCount = 9,
            saturated = false,
            nearEdge = false,
        )

    private fun geometry(): CameraSessionGeometry =
        buildTestGeometry(
            bufferWidthPx = bufferWidthPx,
            bufferHeightPx = bufferHeightPx,
            viewportWidthPx = bufferWidthPx,
            viewportHeightPx = bufferHeightPx,
            intrinsicsResolution =
                analysisBufferIntrinsics(
                    referenceWidthPx = bufferWidthPx,
                    referenceHeightPx = bufferHeightPx,
                    horizontalFovDeg = 66.0,
                    verticalFovDeg = 52.0,
                ),
        )

    /** The real prediction path: catalog directions through `projectStars` for [geometry]. */
    private fun projectFixtureStars(
        geometry: CameraSessionGeometry,
    ): Pair<List<EquatorialStarDirection>, List<PredictedStarProjection>> {
        val context =
            StarProjectionContext.of(
                latitudeRad = Math.toRadians(50.45),
                longitudeRad = Math.toRadians(30.52),
                utcEpochMillis = 1_767_225_600_000L,
                magneticDeclinationRad = Math.toRadians(11.5),
            )
        val candidates =
            (0 until 400).map { index ->
                star(
                    catalogIndex = index,
                    rightAscensionRad = index % 20 * (Math.PI / 10.0),
                    declinationRad = -1.2 + index / 20 * 0.12,
                    magnitude = 1.0 + index % 7,
                )
            }
        val batch = projectStars(stars = candidates, context = context, geometry = geometry)
        return candidates to (batch as StarPredictionBatchResult.Ready).projections
    }

    /** Projections that landed well inside the buffer and far enough apart that no two PSFs merge. */
    private fun wellSeparatedInBuffer(projections: List<PredictedStarProjection>): List<PredictedStarProjection> {
        val kept = mutableListOf<PredictedStarProjection>()
        val inBuffer =
            projections
                .filter { it.imagePoint != null }
                .filter { it.imagePoint!!.x > MARGIN_PX && it.imagePoint!!.x < bufferWidthPx - MARGIN_PX }
                .filter { it.imagePoint!!.y > MARGIN_PX && it.imagePoint!!.y < bufferHeightPx - MARGIN_PX }
                .sortedWith(compareBy({ it.imagePoint!!.x }, { it.imagePoint!!.y }))
        for (projection in inBuffer) {
            val point = projection.imagePoint!!
            val clashes =
                kept.any {
                    abs(it.imagePoint!!.x - point.x) < SEPARATION_PX && abs(it.imagePoint!!.y - point.y) < SEPARATION_PX
                }
            if (!clashes) kept.add(projection)
        }
        return kept.take(MAX_SOURCES)
    }

    /**
     * Pairs each rendered prediction with the detection that came back for it, using the existing
     * metrics utility purely as a nearest-neighbour pairing over a synthetic frame — the one use
     * `DetectionEvaluation.kt` sanctions. It is not, and must not be read as, a matcher.
     */
    private fun pairDetectionsToPredictions(
        input: StarMatcherInput,
        rendered: List<PredictedStarProjection>,
    ): List<Pair<DetectedSource, PredictedStarProjection>> {
        val points = rendered.map { PredictedPointPx(it.catalogIndex, it.imagePoint!!.x, it.imagePoint!!.y) }
        val report = evaluateDetections(input.detections, points, tolerancePx = 2.0)
        return report.matches.map { match -> input.detectionAt(match.detectedIndex) to rendered[match.predictedIndex] }
    }

    private companion object {
        const val MARGIN_PX = 40.0
        const val SEPARATION_PX = 40.0
        const val MAX_SOURCES = 6
        const val HALF_PIXEL = 0.5
        const val PER_SOURCE_TOLERANCE_PX = 0.15
        const val SYSTEMATIC_TOLERANCE_PX = 0.05
        const val SENSITIVITY_MARGIN_PX = 0.4
    }
}
