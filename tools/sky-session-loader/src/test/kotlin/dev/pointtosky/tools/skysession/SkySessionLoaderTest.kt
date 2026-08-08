package dev.pointtosky.tools.skysession

import dev.pointtosky.core.astro.projection.camera.detect.DetectionEvaluationReport
import dev.pointtosky.core.astro.projection.camera.detect.LumaFrame
import dev.pointtosky.core.astro.projection.camera.detect.detectStars
import dev.pointtosky.core.astro.projection.camera.detect.evaluateDetections
import dev.pointtosky.core.astro.projection.camera.detect.toPredictedPointsPx
import dev.pointtosky.core.astro.projection.camera.skylog.SkyFrameReplaySkipReason
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The loader's correctness proof without hardware: a session written to disk in the writer's own
 * layout must produce exactly the metrics the in-memory chain produces from the same pixels and the
 * same recorded predictions, and must withhold — never fabricate — the projection-dependent ones when
 * the log's own honesty gates say it cannot have them.
 */
class SkySessionLoaderTest {
    @TempDir
    lateinit var sessionDirectory: File

    @Test
    fun `session on disk yields the same metrics as the in-memory chain`() {
        val frames =
            listOf(
                SyntheticSession.frame(sequence = 0L, stars = SyntheticSession.defaultStars()),
                SyntheticSession.frame(
                    sequence = 1L,
                    stars =
                        listOf(
                            SyntheticStar(xPx = 200.25, yPx = 120.75, peakAboveBackground = 140.0),
                            SyntheticStar(xPx = 415.5, yPx = 300.5, peakAboveBackground = 95.0),
                            SyntheticStar(xPx = 90.4, yPx = 420.6, peakAboveBackground = 180.0),
                        ),
                ),
            )
        SyntheticSession.write(sessionDirectory, SyntheticSession.header(), frames)

        val report = readyReport(analyzeSkySession(sessionDirectory))

        assertEquals(frames.size, report.frames.size)
        frames.forEachIndexed { index, frame ->
            val expected = inMemoryEvaluation(frame)
            val actual = report.frames[index]
            assertEquals(frame.record.sequence, actual.sequence)
            assertEquals(frame.record.luma.path, actual.lumaPath)
            assertNull(actual.lumaFailure)
            assertNull(actual.projectionSkipReason, "frame ${frame.record.sequence} should have replayed")
            assertEquals(expected.detectedCount, assertNotNull(actual.detectedCount))
            // The whole report, field for field: rate, RMS, max, false positives and every match.
            assertEquals(expected, assertNotNull(actual.evaluation))
        }

        val aggregate = report.aggregate
        val starCount = frames.sumOf { it.stars.size }
        assertEquals(frames.size, aggregate.framesWithPixels)
        assertEquals(frames.size, aggregate.scoredFrameCount)
        assertEquals(starCount, aggregate.predictedCount)
        assertEquals(starCount, aggregate.matchedCount)
        assertEquals(1.0, assertNotNull(aggregate.detectionRate), "every rendered star must be recovered")
        assertEquals(0, aggregate.falsePositiveCount)
        assertTrue(aggregate.projectionSkipCounts.isEmpty())
        val rms = assertNotNull(aggregate.centroidResidualRmsPx)
        assertTrue(rms < 0.2, "centroid residual RMS against the rendered positions was $rms px")
    }

    @Test
    fun `an unknown clock withholds projection metrics and still reports pure detections`() {
        val frame = SyntheticSession.frame(sequence = 0L, stars = SyntheticSession.defaultStars())
        SyntheticSession.write(
            sessionDirectory,
            SyntheticSession.header(clockAlignment = SyntheticSession.unknownClockAlignment),
            listOf(frame),
        )

        val report = readyReport(analyzeSkySession(sessionDirectory))
        val metrics = report.frames.single()

        assertEquals(SkyFrameReplaySkipReason.POSE_CLOCK_UNALIGNED, metrics.projectionSkipReason)
        // Nothing invented in place of the offset the session never measured.
        assertNull(metrics.evaluation)
        assertNull(metrics.replayMaxImageResidualPx)
        // The pixels need no pose, so the detection count survives the gate intact.
        val expectedDetections = inMemoryEvaluation(frame).detectedCount
        assertEquals(expectedDetections, assertNotNull(metrics.detectedCount))

        val aggregate = report.aggregate
        assertEquals(1, aggregate.framesWithPixels)
        assertEquals(expectedDetections, aggregate.detectedSourceCount)
        assertEquals(0, aggregate.scoredFrameCount)
        assertEquals(0, aggregate.predictedCount)
        assertEquals(0, aggregate.matchedCount)
        assertEquals(0, aggregate.falsePositiveCount)
        assertNull(aggregate.detectionRate, "a rate of zero would read as a detector that found nothing")
        assertNull(aggregate.centroidResidualRmsPx)
        assertNull(aggregate.maxCentroidResidualPx)
        assertEquals(mapOf(SkyFrameReplaySkipReason.POSE_CLOCK_UNALIGNED to 1), aggregate.projectionSkipCounts)
    }

    @Test
    fun `a frame with no observer or no declination is gated per frame, not per session`() {
        val stars = SyntheticSession.defaultStars()
        val frames =
            listOf(
                SyntheticSession.frame(sequence = 0L, stars = stars),
                SyntheticSession.frame(sequence = 1L, stars = stars, observer = null),
                SyntheticSession.frame(
                    sequence = 2L,
                    stars = stars,
                    observer = SyntheticSession.observer(magneticDeclinationDeg = null),
                ),
            )
        SyntheticSession.write(sessionDirectory, SyntheticSession.header(), frames)

        val report = readyReport(analyzeSkySession(sessionDirectory))

        assertNotNull(report.frames[0].evaluation)
        assertEquals(SkyFrameReplaySkipReason.OBSERVER_CONTEXT_UNAVAILABLE, report.frames[1].projectionSkipReason)
        assertEquals(
            SkyFrameReplaySkipReason.MAGNETIC_DECLINATION_UNAVAILABLE,
            report.frames[2].projectionSkipReason,
        )
        assertNull(report.frames[1].evaluation)
        assertNull(report.frames[2].evaluation)
        // All three frames still contribute their pixel detections.
        assertEquals(3, report.aggregate.framesWithPixels)
        assertEquals(1, report.aggregate.scoredFrameCount)
        assertEquals(stars.size, report.aggregate.predictedCount)
        assertEquals(
            mapOf(
                SkyFrameReplaySkipReason.OBSERVER_CONTEXT_UNAVAILABLE to 1,
                SkyFrameReplaySkipReason.MAGNETIC_DECLINATION_UNAVAILABLE to 1,
            ),
            report.aggregate.projectionSkipCounts,
        )
    }

    @Test
    fun `a frame whose pixels are missing is counted apart from one that detected nothing`() {
        val frames = listOf(SyntheticSession.frame(sequence = 0L, stars = SyntheticSession.defaultStars()))
        SyntheticSession.write(sessionDirectory, SyntheticSession.header(), frames)
        val record = frames[0].record
        assertTrue(File(sessionDirectory, record.luma.path).delete())

        val report = readyReport(analyzeSkySession(sessionDirectory))
        val metrics = report.frames.single()

        assertEquals(SkyLumaReadFailure.FILE_MISSING, metrics.lumaFailure)
        assertNull(metrics.detectedCount, "no pixels is not a detection count of zero")
        assertNull(metrics.evaluation)
        assertEquals(0, report.aggregate.framesWithPixels)
        assertEquals(mapOf(SkyLumaReadFailure.FILE_MISSING to 1), report.aggregate.lumaFailureCounts)
    }

    @Test
    fun `a directory that is not a session fails with a categorized reason`() {
        assertEquals(
            SkySessionLoadFailure.LOG_MISSING,
            assertIs<SkySessionAnalysisResult.Failed>(analyzeSkySession(sessionDirectory)).reason,
        )
        assertEquals(
            SkySessionLoadFailure.DIRECTORY_MISSING,
            assertIs<SkySessionAnalysisResult.Failed>(
                analyzeSkySession(File(sessionDirectory, "no-such-session")),
            ).reason,
        )
    }

    @Test
    fun `a log with no header line is refused rather than replayed against nothing`() {
        val frame = SyntheticSession.frame(sequence = 0L, stars = SyntheticSession.defaultStars())
        SyntheticSession.write(sessionDirectory, SyntheticSession.header(), listOf(frame))
        val logFile = File(sessionDirectory, SKY_SESSION_LOG_FILE_NAME)
        logFile.writeText(logFile.readLines().drop(1).joinToString(separator = "\n", postfix = "\n"))

        assertEquals(
            SkySessionLoadFailure.HEADER_MISSING,
            assertIs<SkySessionAnalysisResult.Failed>(analyzeSkySession(sessionDirectory)).reason,
        )
    }

    /** The same chain the loader runs, driven straight from the in-memory bytes the fixture rendered. */
    private fun inMemoryEvaluation(frame: SyntheticSession.Frame): DetectionEvaluationReport =
        evaluateDetections(
            detectStars(LumaFrame.forReference(frame.record.luma, frame.data)),
            frame.record.predictedStars.toPredictedPointsPx(),
            DEFAULT_MATCH_TOLERANCE_PX,
        )

    private fun readyReport(result: SkySessionAnalysisResult): SkySessionDetectionReport =
        assertIs<SkySessionAnalysisResult.Ready>(result).report
}
