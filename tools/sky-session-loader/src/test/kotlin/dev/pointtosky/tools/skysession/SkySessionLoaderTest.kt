package dev.pointtosky.tools.skysession

import dev.pointtosky.core.astro.projection.camera.detect.DetectionEvaluationReport
import dev.pointtosky.core.astro.projection.camera.detect.LumaFrame
import dev.pointtosky.core.astro.projection.camera.detect.PredictedPointPx
import dev.pointtosky.core.astro.projection.camera.detect.detectStars
import dev.pointtosky.core.astro.projection.camera.detect.evaluateDetections
import dev.pointtosky.core.astro.projection.camera.detect.toPredictedPointsPx
import dev.pointtosky.core.astro.projection.camera.skylog.SkyFrameRecord
import dev.pointtosky.core.astro.projection.camera.skylog.SkyFrameReplayResult
import dev.pointtosky.core.astro.projection.camera.skylog.SkyFrameReplaySkipReason
import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogHeader
import dev.pointtosky.core.astro.projection.camera.skylog.replaySkySessionFrame
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The loader's correctness proof without hardware.
 *
 * Three properties are pinned here, and they are deliberately separate:
 *  1. a session on disk produces exactly the metrics the in-memory chain produces from the same pixels
 *     and the same **replayed** truth set;
 *  2. the detector is scored against the projection replay recomputes, never against the coordinates
 *     the log recorded — with the recorded-vs-replayed gap reported alongside as its own diagnostic;
 *  3. a frame with no truth set is not a frame the detector scored zero on. Both states exist, and
 *     they read differently.
 */
class SkySessionLoaderTest {
    @TempDir
    lateinit var sessionDirectory: File

    @Test
    fun `self-consistent session on disk yields the same metrics as the in-memory chain`() {
        val header = SyntheticSession.header()
        val frames =
            listOf(
                SyntheticSession.selfConsistentFrame(sequence = 0L, header = header, starCount = 5),
                SyntheticSession.selfConsistentFrame(sequence = 1L, header = header, starCount = 3),
            )
        SyntheticSession.write(sessionDirectory, header, frames)

        val report = readyReport(analyzeSkySession(sessionDirectory))

        assertEquals(frames.size, report.frames.size)
        frames.forEachIndexed { index, frame ->
            val expected = inMemoryEvaluation(header, frame)
            val actual = report.frames[index]
            assertEquals(frame.record.sequence, actual.sequence)
            assertEquals(frame.record.luma.path, actual.lumaPath)
            assertNull(actual.lumaFailure)
            assertNull(actual.projectionSkipReason, "frame ${frame.record.sequence} should have replayed")
            assertNull(actual.evaluationUnavailable)
            assertEquals(expected.detectedCount, assertNotNull(actual.detectedCount))
            // The whole report, field for field: rate, RMS, max, false positives and every match.
            assertEquals(expected, assertNotNull(actual.evaluation))
            // The fixture is genuinely self-consistent: what it recorded is what replay recomputes.
            assertEquals(0.0, assertNotNull(actual.replayMaxImageResidualPx), absoluteTolerance = 1e-9)
            assertEquals(0, actual.replayClassificationMismatchCount)
        }

        val aggregate = report.aggregate
        val starCount = frames.sumOf { it.renderedStars.size }
        assertEquals(frames.size, aggregate.framesWithPixels)
        assertEquals(frames.size, aggregate.scoredFrameCount)
        assertEquals(starCount, aggregate.predictedCount)
        assertEquals(starCount, aggregate.matchedCount)
        assertEquals(1.0, assertNotNull(aggregate.detectionRate), "every rendered star must be recovered")
        assertEquals(0, aggregate.falsePositiveCount)
        assertEquals(0, aggregate.framesWithoutObservablePredictions)
        assertTrue(aggregate.projectionSkipCounts.isEmpty())
        val rms = assertNotNull(aggregate.centroidResidualRmsPx)
        assertTrue(rms < 0.2, "centroid residual RMS against the replayed positions was $rms px")
        assertEquals(0, aggregate.replayClassificationMismatchCount)
    }

    @Test
    fun `scores the detector against the replayed projection, not the recorded coordinates`() {
        // A stale record: its pixels sit where replay says the stars are, its written-down coordinates
        // do not. Scoring against the recorded values would report this detector as having found
        // nothing, purely because the log's own bookkeeping drifted.
        val offsetPx = 7.5
        val header = SyntheticSession.header()
        val frame = SyntheticSession.inconsistentFrame(sequence = 0L, recordedOffsetPx = offsetPx, header = header)
        SyntheticSession.write(sessionDirectory, header, listOf(frame))

        val report = readyReport(analyzeSkySession(sessionDirectory))
        val metrics = report.frames.single()
        val evaluation = assertNotNull(metrics.evaluation)

        // Detector metric: measured against the replayed truth set, so the detector scores clean.
        assertEquals(frame.renderedStars.size, evaluation.predictedCount)
        assertEquals(frame.renderedStars.size, evaluation.matchedCount)
        assertEquals(1.0, assertNotNull(evaluation.detectionRate))
        assertTrue(assertNotNull(evaluation.centroidResidualRmsPx) < 0.2)
        // Field for field identical to scoring against the replayed truth set — which is the claim.
        assertEquals(inMemoryEvaluation(header, frame), evaluation)

        // Integrity metric: the deliberate discrepancy is reported, and reported separately.
        assertEquals(offsetPx, assertNotNull(metrics.replayMaxImageResidualPx), absoluteTolerance = 1e-6)
        assertEquals(offsetPx, assertNotNull(metrics.replayRmsImageResidualPx), absoluteTolerance = 1e-6)
        assertEquals(offsetPx, assertNotNull(report.aggregate.maxReplayImageResidualPx), absoluteTolerance = 1e-6)
        // The two must not be the same number: a large replay residual with a small detector residual
        // is precisely the state this test exists to keep legible.
        assertTrue(assertNotNull(metrics.replayMaxImageResidualPx) > assertNotNull(evaluation.maxCentroidResidualPx))

        // And the proof that the distinction matters: the recorded coordinates would have scored zero.
        val againstRecorded =
            evaluateDetections(
                detectStars(LumaFrame.forReference(frame.record.luma, frame.data)),
                frame.record.predictedStars.toPredictedPointsPx(),
                DEFAULT_MATCH_TOLERANCE_PX,
            )
        assertEquals(0, againstRecorded.matchedCount, "the recorded coordinates are ${offsetPx}px away")
    }

    @Test
    fun `a replay-ready frame with no observable source is not a frame the detector scored zero on`() {
        val header = SyntheticSession.header()
        val frame = SyntheticSession.frameWithNoObservablePredictions(sequence = 0L, header = header)
        SyntheticSession.write(sessionDirectory, header, listOf(frame))

        val report = readyReport(analyzeSkySession(sessionDirectory))
        val metrics = report.frames.single()

        // Replay itself succeeded, so this is not a skip reason.
        assertNull(metrics.projectionSkipReason)
        assertEquals(SkyFrameEvaluationUnavailable.NO_OBSERVABLE_PREDICTIONS, metrics.evaluationUnavailable)
        assertNull(metrics.evaluation, "an empty truth set defines no rate and no false positive")
        // Pure pixel detection is unaffected: the stars are in the luma either way.
        val detected = assertNotNull(metrics.detectedCount)
        assertEquals(frame.renderedStars.size, detected)

        val aggregate = report.aggregate
        assertEquals(1, aggregate.framesWithPixels)
        assertEquals(detected, aggregate.detectedSourceCount)
        assertEquals(1, aggregate.framesWithoutObservablePredictions)
        assertEquals(0, aggregate.scoredFrameCount)
        assertEquals(0, aggregate.predictedCount)
        assertEquals(0, aggregate.matchedCount)
        assertEquals(
            0,
            aggregate.falsePositiveCount,
            "detections with nothing to pair against are not false positives",
        )
        assertNull(aggregate.detectionRate)
        assertNull(aggregate.centroidResidualRmsPx)
    }

    @Test
    fun `a truth set the detector found nothing in is a scored frame with a rate of zero`() {
        val header = SyntheticSession.header()
        // Predictions in place, pixels deliberately starless: the detector genuinely missed everything.
        val frame =
            SyntheticSession.selfConsistentFrame(
                sequence = 0L,
                header = header,
                starCount = 4,
                renderStars = false,
                noiseSigma = 0.0,
            )
        SyntheticSession.write(sessionDirectory, header, listOf(frame))

        val report = readyReport(analyzeSkySession(sessionDirectory))
        val metrics = report.frames.single()
        val evaluation = assertNotNull(metrics.evaluation, "a non-empty truth set is scorable")

        assertNull(metrics.evaluationUnavailable)
        assertEquals(0, assertNotNull(metrics.detectedCount))
        assertEquals(4, evaluation.predictedCount)
        assertEquals(0, evaluation.matchedCount)
        assertEquals(0.0, assertNotNull(evaluation.detectionRate), "zero of four recovered is a real rate")
        assertNull(evaluation.centroidResidualRmsPx, "no matches means no residual, not a residual of zero")

        val aggregate = report.aggregate
        assertEquals(1, aggregate.scoredFrameCount)
        assertEquals(4, aggregate.predictedCount)
        assertEquals(0.0, assertNotNull(aggregate.detectionRate))
        assertEquals(0, aggregate.framesWithoutObservablePredictions)
    }

    @Test
    fun `an unknown clock relationship withholds projection metrics and still reports pure detections`() {
        val header = SyntheticSession.header(clockAlignment = SyntheticSession.unknownClockAlignment)
        // Positions still come from a real projection run; only the session's clock claim differs.
        val frame = SyntheticSession.selfConsistentFrame(sequence = 0L, header = SyntheticSession.header())
        SyntheticSession.write(sessionDirectory, header, listOf(frame))

        val report = readyReport(analyzeSkySession(sessionDirectory))
        val metrics = report.frames.single()

        assertEquals(SkyFrameReplaySkipReason.POSE_CLOCK_UNALIGNED, metrics.projectionSkipReason)
        assertEquals(SkyFrameEvaluationUnavailable.PROJECTION_UNAVAILABLE, metrics.evaluationUnavailable)
        // Nothing invented in place of the offset the session never measured.
        assertNull(metrics.evaluation)
        assertNull(metrics.replayMaxImageResidualPx)
        assertNull(metrics.replayRmsImageResidualPx)
        assertNull(metrics.replayClassificationMismatchCount)
        // The pixels need no pose, so the detection count survives the gate intact.
        assertEquals(frame.renderedStars.size, assertNotNull(metrics.detectedCount))

        val aggregate = report.aggregate
        assertEquals(1, aggregate.framesWithPixels)
        assertEquals(0, aggregate.scoredFrameCount)
        assertEquals(0, aggregate.predictedCount)
        assertEquals(0, aggregate.falsePositiveCount)
        assertNull(aggregate.detectionRate, "a rate of zero would read as a detector that found nothing")
        assertNull(aggregate.centroidResidualRmsPx)
        assertNull(aggregate.maxReplayImageResidualPx)
        assertEquals(mapOf(SkyFrameReplaySkipReason.POSE_CLOCK_UNALIGNED to 1), aggregate.projectionSkipCounts)
    }

    @Test
    fun `a frame with no observer or no declination is gated per frame, not per session`() {
        val header = SyntheticSession.header()
        val frames =
            listOf(
                SyntheticSession.selfConsistentFrame(sequence = 0L, header = header),
                SyntheticSession.selfConsistentFrame(sequence = 1L, header = header).withObserver(null),
                SyntheticSession
                    .selfConsistentFrame(sequence = 2L, header = header)
                    .withObserver(SyntheticSession.observer(magneticDeclinationDeg = null)),
            )
        SyntheticSession.write(sessionDirectory, header, frames)

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
        val header = SyntheticSession.header()
        val frames = listOf(SyntheticSession.selfConsistentFrame(sequence = 0L, header = header))
        SyntheticSession.write(sessionDirectory, header, frames)
        val record = frames[0].record
        assertTrue(File(sessionDirectory, record.luma.path).delete())

        val report = readyReport(analyzeSkySession(sessionDirectory))
        val metrics = report.frames.single()

        assertEquals(SkyLumaReadFailure.FILE_MISSING, metrics.lumaFailure)
        assertEquals(SkyFrameEvaluationUnavailable.LUMA_UNAVAILABLE, metrics.evaluationUnavailable)
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
        val header = SyntheticSession.header()
        val frame = SyntheticSession.selfConsistentFrame(sequence = 0L, header = header)
        SyntheticSession.write(sessionDirectory, header, listOf(frame))
        val logFile = File(sessionDirectory, SKY_SESSION_LOG_FILE_NAME)
        logFile.writeText(logFile.readLines().drop(1).joinToString(separator = "\n", postfix = "\n"))

        assertEquals(
            SkySessionLoadFailure.HEADER_MISSING,
            assertIs<SkySessionAnalysisResult.Failed>(analyzeSkySession(sessionDirectory)).reason,
        )
    }

    /** The same chain the loader runs, driven straight from the in-memory bytes the fixture rendered. */
    private fun inMemoryEvaluation(
        header: SkySessionLogHeader,
        frame: SyntheticSession.Frame,
    ): DetectionEvaluationReport =
        evaluateDetections(
            detectStars(LumaFrame.forReference(frame.record.luma, frame.data)),
            replayedTruth(header, frame.record),
            DEFAULT_MATCH_TOLERANCE_PX,
        )

    /** The truth set SKY-3 must use: the projection recomputed offline, not anything recorded. */
    private fun replayedTruth(
        header: SkySessionLogHeader,
        record: SkyFrameRecord,
    ): List<PredictedPointPx> {
        val replayed = assertIs<SkyFrameReplayResult.Ready>(replaySkySessionFrame(header, record))
        return replayed.projections.toPredictedPointsPx()
    }

    private fun readyReport(result: SkySessionAnalysisResult): SkySessionDetectionReport =
        assertIs<SkySessionAnalysisResult.Ready>(result).report
}
