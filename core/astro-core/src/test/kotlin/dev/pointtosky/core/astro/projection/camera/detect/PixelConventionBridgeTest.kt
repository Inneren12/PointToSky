package dev.pointtosky.core.astro.projection.camera.detect

import dev.pointtosky.core.astro.projection.camera.PixelPoint
import dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionBatchResult
import dev.pointtosky.core.astro.projection.camera.prediction.StarProjectionContext
import dev.pointtosky.core.astro.projection.camera.prediction.analysisBufferIntrinsics
import dev.pointtosky.core.astro.projection.camera.prediction.buildTestGeometry
import dev.pointtosky.core.astro.projection.camera.prediction.projectStars
import dev.pointtosky.core.astro.projection.camera.prediction.star
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the SKY-2 detector's output coordinates to the **production** buffer-pixel convention, rather
 * than to a synthetic point authored under the same assumption as the detector.
 *
 * The convention is stated canonically in `PixelGeometry.kt`'s file KDoc and
 * `docs/camera_coordinate_calibration_contract.md` §9.2: coordinates are continuous image-edge
 * coordinates in `[0, W] x [0, H]`, so raster sample `[x, y]` is centred at `(x + 0.5, y + 0.5)`. Both
 * the pinhole projection and the detector work in it — but a detector test that only ever compares
 * itself to `SyntheticFrameRenderer` cannot prove that, because the renderer was written to the same
 * rule. If both were wrong by half a pixel in the same direction, every other test in this package
 * would still pass.
 *
 * So these tests take a [PixelPoint] out of the real [PinholeProjectionModel] / [projectStars] math,
 * render a point source at exactly that coordinate, detect it, and require the round trip to return the
 * number it started from. A half-pixel shift anywhere in that chain fails them — [SENSITIVITY_MARGIN_PX]
 * is asserted explicitly so the discriminating power is not merely assumed.
 */
class PixelConventionBridgeTest {
    private val bufferWidthPx = 640
    private val bufferHeightPx = 480

    private fun geometry() =
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

    @Test
    fun `the pinhole principal point is the buffer centre under the shared convention`() {
        val model = PinholeProjectionModel.forGeometry(geometry())

        // The on-axis ray lands on the principal point, which forGeometry defaults to width/2, height/2.
        // Under the edge-coordinate convention that is the buffer's exact geometric centre; under a
        // pixel-centre convention the centre would be 319.5/239.5 instead. This is the numeric fact the
        // detector's own +0.5 offset has to agree with.
        val onAxis = model.project(normalizedX = 0.0, normalizedY = 0.0)

        assertEquals(320.0, onAxis.x)
        assertEquals(240.0, onAxis.y)
        assertEquals(bufferWidthPx / 2.0, onAxis.x)
        assertEquals(bufferHeightPx / 2.0, onAxis.y)
    }

    @Test
    fun `a source rendered at a projected pixel point is detected back at that same point`() {
        val model = PinholeProjectionModel.forGeometry(geometry())
        // Deliberately asymmetric, off-centre rays: a half-pixel error is invisible at a point that is
        // symmetric about the pixel grid, so none of these sits on a whole or half coordinate by accident.
        val projected =
            listOf(
                model.project(normalizedX = -0.31, normalizedY = -0.22),
                model.project(normalizedX = 0.17, normalizedY = -0.35),
                model.project(normalizedX = 0.44, normalizedY = 0.13),
                model.project(normalizedX = -0.19, normalizedY = 0.29),
                model.project(normalizedX = 0.05, normalizedY = 0.41),
            )

        assertNoSystematicOffset(projected)
    }

    @Test
    fun `a source rendered at a projectStars image point is detected back at that same point`() {
        // The full production path this time — real star directions through the real observing context,
        // not just the pinhole model in isolation.
        val geometry = geometry()
        val context =
            StarProjectionContext.of(
                latitudeRad = Math.toRadians(50.45),
                longitudeRad = Math.toRadians(30.52),
                utcEpochMillis = 1_767_225_600_000L,
                magneticDeclinationRad = Math.toRadians(11.5),
            )
        val directions =
            (0 until 400).map { index ->
                star(
                    catalogIndex = index,
                    rightAscensionRad = index % 20 * (Math.PI / 10.0),
                    declinationRad = -1.2 + index / 20 * 0.12,
                )
            }

        val batch = projectStars(stars = directions, context = context, geometry = geometry)
        val ready = batch as StarPredictionBatchResult.Ready
        val inBuffer =
            ready.projections
                .mapNotNull { it.imagePoint }
                .filter { it.x > MARGIN_PX && it.x < bufferWidthPx - MARGIN_PX }
                .filter { it.y > MARGIN_PX && it.y < bufferHeightPx - MARGIN_PX }
        val wellSeparated = pickWellSeparated(inBuffer)

        assertTrue(
            wellSeparated.size >= 3,
            "the fixture must project at least 3 well-separated in-buffer stars; got ${wellSeparated.size}",
        )
        assertNoSystematicOffset(wellSeparated)
    }

    /**
     * Renders a star at each of [points], detects them, and requires every centroid back within
     * [PER_SOURCE_TOLERANCE_PX] with no systematic bias on either axis — then asserts that a half-pixel
     * shift *would* have been caught, so a passing run is evidence rather than a tolerance wide enough to
     * swallow the error it exists to find.
     */
    private fun assertNoSystematicOffset(points: List<PixelPoint>) {
        val stars = points.map { SyntheticStar(xPx = it.x, yPx = it.y, peakAboveBackground = 140.0) }
        val frame =
            renderSyntheticFrame(
                widthPx = bufferWidthPx,
                heightPx = bufferHeightPx,
                rowStridePx = bufferWidthPx + 64,
                background = SyntheticBackground.Uniform(22.0),
                stars = stars,
                noise = SyntheticNoise.Gaussian(sigma = 2.0),
                seed = 314159L,
            )

        val detections = detectStars(frame)
        val predicted = points.mapIndexed { index, p -> PredictedPointPx(index, p.x, p.y) }
        val report = evaluateDetections(detections, predicted, tolerancePx = 2.0)
        assertEquals(points.size, report.matchedCount, "every projected point must be recovered")

        val offsetsX = report.matches.map { detections[it.detectedIndex].xPx - points[it.predictedIndex].x }
        val offsetsY = report.matches.map { detections[it.detectedIndex].yPx - points[it.predictedIndex].y }

        for (i in offsetsX.indices) {
            assertTrue(
                abs(offsetsX[i]) < PER_SOURCE_TOLERANCE_PX && abs(offsetsY[i]) < PER_SOURCE_TOLERANCE_PX,
                "source $i came back offset by (${offsetsX[i]}, ${offsetsY[i]}) px from its projected point",
            )
        }

        val meanX = offsetsX.average()
        val meanY = offsetsY.average()
        assertTrue(abs(meanX) < SYSTEMATIC_TOLERANCE_PX, "systematic X offset of $meanX px against production coords")
        assertTrue(abs(meanY) < SYSTEMATIC_TOLERANCE_PX, "systematic Y offset of $meanY px against production coords")

        // The test would be worthless if its tolerance could absorb a half-pixel convention mismatch.
        // Assert directly that it could not: a detector shifted by +0.5 px on either axis fails above.
        assertTrue(
            abs(meanX + HALF_PIXEL) > SENSITIVITY_MARGIN_PX && abs(meanY + HALF_PIXEL) > SENSITIVITY_MARGIN_PX,
            "this test could not distinguish a half-pixel shift (meanX=$meanX, meanY=$meanY)",
        )
    }

    /** Greedily keeps points at least [SEPARATION_PX] apart so no two rendered PSFs merge into one source. */
    private fun pickWellSeparated(points: List<PixelPoint>): List<PixelPoint> {
        val kept = mutableListOf<PixelPoint>()
        for (point in points.sortedWith(compareBy({ it.x }, { it.y }))) {
            if (kept.none { abs(it.x - point.x) < SEPARATION_PX && abs(it.y - point.y) < SEPARATION_PX }) {
                kept.add(point)
            }
        }
        return kept.take(MAX_BRIDGE_SOURCES)
    }

    private companion object {
        const val MARGIN_PX = 40.0
        const val SEPARATION_PX = 40.0
        const val MAX_BRIDGE_SOURCES = 6
        const val HALF_PIXEL = 0.5

        /** Every individual centroid must land this close to the coordinate production projected. */
        const val PER_SOURCE_TOLERANCE_PX = 0.15

        /** The mean offset must be this close to zero — a convention mismatch would put it at 0.5. */
        const val SYSTEMATIC_TOLERANCE_PX = 0.05

        /** How far a half-pixel shift must sit outside [SYSTEMATIC_TOLERANCE_PX] for the test to be meaningful. */
        const val SENSITIVITY_MARGIN_PX = 0.4
    }
}
