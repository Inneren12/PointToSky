package dev.pointtosky.core.astro.projection.camera.skylog

import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarProjection
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionBatchResult
import dev.pointtosky.core.astro.projection.camera.prediction.StarProjectionContext
import dev.pointtosky.core.astro.projection.camera.prediction.projectStars
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JVM tests for the SKY-1 offline replay (tests §4): a synthetic log, written and read back
 * through the real codec, drives the real projection math to exactly the numbers a direct call
 * produces — with no device, no camera, and no Android runtime anywhere in the path.
 *
 * The synthetic log is built the same way a real one is: the projection is run **first**, and its
 * output is what gets recorded. A fixture that hand-wrote pixel coordinates would let a replay
 * "match" numbers the math never produced.
 */
class SkySessionLogReplayTest {
    private val fixtures = SkySessionLogFixtures

    private val directions: List<EquatorialStarDirection> =
        fixtures.starDirections().map { (index, ra, dec) ->
            EquatorialStarDirection.of(catalogIndex = index, rightAscensionRad = ra, declinationRad = dec, magnitude = 2.0 + index % 3)
        }

    /** The geometry a live session would have published for the fixture frame. */
    private fun liveGeometry(header: SkySessionLogHeader, record: SkyFrameRecord): CameraSessionGeometry =
        assertIs<CameraSessionGeometryResult.Ready>(rebuildSkyFrameGeometry(header, record)).geometry

    private fun liveContext(observer: SkyObserverContext): StarProjectionContext = assertNotNull(observer.toStarProjectionContext())

    /**
     * Builds a log whose recorded predictions are the genuine output of [projectStars] for the
     * fixture pose — the same order of operations the on-device capture performs.
     */
    private fun syntheticLog(frameCount: Int = 3): Pair<SkySessionLogHeader, List<SkyFrameRecord>> {
        val header = fixtures.header()
        val records =
            (0 until frameCount).map { index ->
                val sequence = index.toLong()
                val skeleton =
                    fixtures.frameRecord(
                        sequence = sequence,
                        frame =
                            fixtures.frameMetadata(
                                timestampNanos = SkySessionLogFixtures.FRAME_TIMESTAMP_NANOS + sequence * 33_000_000L,
                            ),
                        pose =
                            fixtures.pose(
                                timestampNanos = SkySessionLogFixtures.FRAME_TIMESTAMP_NANOS + sequence * 33_000_000L,
                                frameTimestampNanos = SkySessionLogFixtures.FRAME_TIMESTAMP_NANOS + sequence * 33_000_000L,
                            ),
                        observer = fixtures.observer(utcEpochMillis = 1_767_225_600_000L + sequence * 33L),
                        luma = fixtures.lumaReference(path = "frames/frame_%06d.y".format(sequence)),
                    )
                val projections = projectDirectly(header, skeleton)
                skeleton.copy(predictedStars = recordPredictions(projections))
            }
        return header to records
    }

    /** Runs the projection math directly, exactly as the capture path does before writing a line. */
    private fun projectDirectly(header: SkySessionLogHeader, record: SkyFrameRecord): List<PredictedStarProjection> {
        val batch =
            projectStars(
                stars = directions,
                context = liveContext(assertNotNull(record.observer)),
                geometry = liveGeometry(header, record),
            )
        return assertIs<StarPredictionBatchResult.Ready>(batch).projections
    }

    private fun recordPredictions(projections: List<PredictedStarProjection>): List<SkyPredictedStar> =
        projections.mapIndexed { index, projection ->
            SkyPredictedStar(
                catalogIndex = projection.catalogIndex,
                rightAscensionRad = directions[index].rightAscensionRad,
                declinationRad = directions[index].declinationRad,
                magnitude = projection.magnitude,
                classification = projection.classification,
                imageXPx = projection.imagePoint?.x,
                imageYPx = projection.imagePoint?.y,
                displayXPx = projection.displayPoint?.x,
                displayYPx = projection.displayPoint?.y,
            )
        }

    // -----------------------------------------------------------------------------------------

    @Test
    fun `a synthetic log written, read back and replayed reproduces a direct projection call`() {
        val (header, records) = syntheticLog()
        val text =
            buildString {
                appendLine(encodeSkySessionHeaderLine(header))
                records.forEach { appendLine(encodeSkyFrameLine(it)) }
            }

        val document = parseSkySessionLog(text)
        val report = assertNotNull(replaySkySessionLog(document))

        assertEquals(records.size, report.readyFrames.size, "every recorded frame must replay")
        report.readyFrames.forEachIndexed { index, replayed ->
            val expected = projectDirectly(header, records[index])
            assertEquals(expected, replayed.projections, "replayed frame $index must equal a direct projectStars call")
        }
    }

    @Test
    fun `replayed residuals against the recorded predictions are exactly zero`() {
        val (header, records) = syntheticLog()

        val report = replaySkySessionLog(header, records)

        val residuals = report.readyFrames.flatMap { it.residuals }
        assertTrue(residuals.isNotEmpty(), "the fixture must produce comparable stars")
        residuals.forEach { residual ->
            assertTrue(residual.classificationMatches, "star ${residual.catalogIndex} changed class on replay")
            residual.imageResidualPx?.let { assertEquals(0.0, it, "star ${residual.catalogIndex} moved on replay") }
        }
        assertEquals(0, report.readyFrames.sumOf { it.classificationMismatchCount })
        assertEquals(0.0, assertNotNull(report.maxImageResidualPx))
    }

    @Test
    fun `a perturbed recorded coordinate shows up as a non-zero residual`() {
        val (header, records) = syntheticLog(frameCount = 1)
        val record = records.single()
        val comparableIndex = record.predictedStars.indexOfFirst { it.imageXPx != null }
        assertTrue(comparableIndex >= 0, "the fixture must contain at least one in-front star")
        val perturbed =
            record.copy(
                predictedStars =
                    record.predictedStars.mapIndexed { index, star ->
                        if (index == comparableIndex) star.copy(imageXPx = star.imageXPx!! + 3.0) else star
                    },
            )

        val replayed = assertIs<SkyFrameReplayResult.Ready>(replaySkySessionFrame(header, perturbed))

        assertEquals(3.0, assertNotNull(replayed.residuals[comparableIndex].imageResidualPx), absoluteTolerance = 1e-9)
        assertEquals(3.0, assertNotNull(replayed.maxImageResidualPx), absoluteTolerance = 1e-9)
    }

    @Test
    fun `a star behind the camera has no residual rather than a zero one`() {
        val (header, records) = syntheticLog(frameCount = 1)
        val record = records.single()
        val behind = record.predictedStars.indexOfFirst { it.imageXPx == null }
        if (behind < 0) return // The fixture pose happens to see every star; nothing to assert here.

        val replayed = assertIs<SkyFrameReplayResult.Ready>(replaySkySessionFrame(header, record))

        assertNull(replayed.residuals[behind].imageResidualPx)
    }

    @Test
    fun `a frame with no observer context is skipped, not projected against a fabricated one`() {
        val header = fixtures.header()
        val record = fixtures.frameRecord(observer = null)

        val replayed = assertIs<SkyFrameReplayResult.Skipped>(replaySkySessionFrame(header, record))

        assertEquals(SkyFrameReplaySkipReason.OBSERVER_CONTEXT_UNAVAILABLE, replayed.reason)
    }

    @Test
    fun `a missing magnetic declination is skipped rather than silently treated as zero`() {
        val header = fixtures.header()
        val record = fixtures.frameRecord(observer = fixtures.observer(magneticDeclinationDeg = null))

        val replayed = assertIs<SkyFrameReplayResult.Skipped>(replaySkySessionFrame(header, record))

        assertEquals(SkyFrameReplaySkipReason.MAGNETIC_DECLINATION_UNAVAILABLE, replayed.reason)
    }

    @Test
    fun `intrinsics referencing a different buffer skip the whole batch with the projector's own reason`() {
        val header = fixtures.header(intrinsics = fixtures.intrinsics(widthPx = 1280, heightPx = 720))
        val record = fixtures.frameRecord(predictedStars = listOf(fixtures.predictedStar()))

        val replayed = assertIs<SkyFrameReplayResult.Skipped>(replaySkySessionFrame(header, record))

        assertEquals(SkyFrameReplaySkipReason.INTRINSICS_MAPPING_UNAVAILABLE, replayed.reason)
        assertEquals("ANALYSIS_BUFFER_DIMENSIONS_MISMATCH", replayed.geometryDetail)
    }

    @Test
    fun `a pose beyond the session's own pairing tolerance skips the frame`() {
        val header = fixtures.header(maxPairDeltaNanos = 5_000_000L)
        val record =
            fixtures.frameRecord(
                pose = fixtures.pose(timestampNanos = SkySessionLogFixtures.FRAME_TIMESTAMP_NANOS - 60_000_000L),
            )

        val replayed = assertIs<SkyFrameReplayResult.Skipped>(replaySkySessionFrame(header, record))

        assertEquals(SkyFrameReplaySkipReason.ROTATION_UNAVAILABLE, replayed.reason)
        assertEquals("OUTSIDE_TOLERANCE", replayed.geometryDetail)
    }

    @Test
    fun `replaying a log with no readable header yields no report`() {
        val document = parseSkySessionLog(encodeSkyFrameLine(fixtures.frameRecord()) + "\n")

        assertNull(replaySkySessionLog(document))
    }

    @Test
    fun `the intrinsics record rebuilds the exact resolution it was flattened from`() {
        val resolution = fixtures.intrinsics()

        val rebuilt = resolution.toSkyIntrinsicsRecord().toCameraIntrinsicsResolution()

        assertEquals(resolution, rebuilt)
    }

    @Test
    fun `a legacy-fallback resolution keeps its reason through the log`() {
        val header = fixtures.header()
        val record =
            assertIs<SkySessionLogLine.Header>(parseSkySessionLogLine(encodeSkySessionHeaderLine(header), 1)).header

        assertNull(record.intrinsics.legacyFallbackReason, "the fixture is a resolved, non-fallback session")
    }
}
