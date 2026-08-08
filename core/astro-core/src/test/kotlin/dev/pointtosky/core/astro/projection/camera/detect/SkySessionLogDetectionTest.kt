package dev.pointtosky.core.astro.projection.camera.detect

import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * SKY-2 against the frozen SKY-1 contract: a frame described by a real [SkySessionLogFixtures]
 * `SkyLumaReference`, with the recorded `SkyPredictedStar` entries as the truth set.
 *
 * The point is to prove the detector consumes the capture contract as written — the reference's own
 * geometry, its stride, its byte length, and the image-space pixel convention `imageXPx`/`imageYPx` are
 * expressed in — without the contract having to change to accommodate it. Nothing here writes a log,
 * touches the codec, or asserts anything about the projection: the residual measured is the detector's
 * distance from a *given* position, and `SkySessionLogReplayTest` independently verifies that those
 * positions are what the projection math produces.
 */
class SkySessionLogDetectionTest {
    // Sub-pixel positions inside the fixture's 640x480 analysis buffer, well clear of the borders and of
    // each other so no two footprints merge.
    private val starPositions =
        listOf(
            120.5 to 90.25,
            305.75 to 160.5,
            480.2 to 250.8,
            210.6 to 380.4,
            560.5 to 400.5,
        )

    @Test
    fun `detects recorded predicted stars in a frame read through a SkyLumaReference`() {
        val rowStridePx = 704
        val reference = SkySessionLogFixtures.lumaReference(rowStridePx = rowStridePx)
        val stars =
            starPositions.mapIndexed { index, (x, y) ->
                SyntheticStar(xPx = x, yPx = y, peakAboveBackground = 120.0 + index * 10.0)
            }
        val data =
            renderSyntheticFrameData(
                widthPx = reference.widthPx,
                heightPx = reference.heightPx,
                rowStridePx = reference.rowStridePx,
                background =
                    SyntheticBackground.LinearGradient(
                        levelAtOrigin = 18.0,
                        levelAtOpposite = 120.0,
                        widthPx = reference.widthPx,
                        heightPx = reference.heightPx,
                    ),
                stars = stars,
                noise = SyntheticNoise.Gaussian(sigma = 2.5),
                seed = 606L,
            )

        val frame = LumaFrame.forReference(reference, data)
        val record =
            SkySessionLogFixtures.frameRecord(
                luma = reference,
                predictedStars =
                    starPositions.mapIndexed { index, (x, y) ->
                        SkySessionLogFixtures.predictedStar(
                            catalogIndex = 100 + index,
                            imageXPx = x,
                            imageYPx = y,
                        )
                    },
            )

        val detections = detectStars(frame)
        val report = evaluateDetections(detections, record.predictedStars.toPredictedPointsPx(), tolerancePx = 2.0)

        assertEquals(starPositions.size, report.predictedCount)
        assertEquals(1.0, assertNotNull(report.detectionRate), "every recorded star must be recovered")
        assertEquals(0, report.falsePositiveCount)
        val rms = assertNotNull(report.centroidResidualRmsPx)
        assertTrue(rms < 0.2, "centroid residual RMS against the recorded positions was $rms px")
    }

    @Test
    fun `works per frame, with no clock alignment involved`() {
        // The detector takes one frame's luma plus that frame's predicted stars and nothing else. That is
        // what lets it be developed now: whether a real Pixel 9 session reports a usable
        // SENSOR_INFO_TIMESTAMP_SOURCE decides whether replay can rebuild geometry, and has no bearing on
        // anything measured here.
        val reference = SkySessionLogFixtures.lumaReference()
        val star = SyntheticStar(xPx = 320.5, yPx = 240.5, peakAboveBackground = 150.0)
        val data =
            renderSyntheticFrameData(
                widthPx = reference.widthPx,
                heightPx = reference.heightPx,
                rowStridePx = reference.rowStridePx,
                stars = listOf(star),
                noise = SyntheticNoise.Gaussian(sigma = 2.0),
                seed = 9L,
            )

        val unalignedRecord =
            SkySessionLogFixtures.frameRecord(
                luma = reference,
                // A pose the session could never place on the frame clock; replay would skip this frame.
                pose = SkySessionLogFixtures.pose(timestampNanos = 5_000_000_000L),
                predictedStars =
                    listOf(SkySessionLogFixtures.predictedStar(imageXPx = star.xPx, imageYPx = star.yPx)),
            )

        val report =
            evaluateDetections(
                detectStars(LumaFrame.forReference(reference, data)),
                unalignedRecord.predictedStars.toPredictedPointsPx(),
                tolerancePx = 2.0,
            )

        assertEquals(1, report.matchedCount)
    }
}
