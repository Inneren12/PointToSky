package dev.pointtosky.tools.skysession

import dev.pointtosky.core.astro.projection.camera.detect.DetectionEvaluationReport
import dev.pointtosky.core.astro.projection.camera.detect.PredictedPointPx
import dev.pointtosky.core.astro.projection.camera.detect.StarDetectorConfig
import dev.pointtosky.core.astro.projection.camera.detect.detectStars
import dev.pointtosky.core.astro.projection.camera.detect.evaluateDetections
import dev.pointtosky.core.astro.projection.camera.detect.toPredictedPointsPx
import dev.pointtosky.core.astro.projection.camera.skylog.SkyFrameRecord
import dev.pointtosky.core.astro.projection.camera.skylog.SkyFrameReplayResult
import dev.pointtosky.core.astro.projection.camera.skylog.SkyFrameReplaySkipReason
import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogHeader
import dev.pointtosky.core.astro.projection.camera.skylog.replaySkySessionLog
import java.io.File
import kotlin.math.sqrt

/**
 * SKY-3: drives the existing parse -> replay -> detect -> evaluate chain over a session directory.
 *
 * Everything measured here is computed by `:core:astro-core`: [detectStars] finds the sources,
 * [replaySkySessionLog] recomputes the projection offline, and [evaluateDetections] scores one against
 * the other. This file only sequences them and counts.
 *
 * ## Where each number comes from
 * **Pure detection** comes from the pixels: how many point sources are in a frame's luma. It needs no
 * pose, no observer and no clock, so [SkySessionFrameMetrics.detectedCount] is reported for every frame
 * whose file could be read, including frames replay refused.
 *
 * **Projection truth** comes from a *successful offline replay*, never from the log's recorded pixel
 * coordinates. [SkyFrameReplayResult.Ready.projections] is what the current math produces from the
 * frame's own pose, observer and intrinsics; the recorded `imageXPx`/`imageYPx` are what some earlier
 * build wrote down. Where a session is intact the two agree, and `Ready` explicitly does **not** assert
 * that they do — it carries the disagreement as residuals and a classification-mismatch count. Scoring
 * against the recorded values would let a stale, hand-edited or mis-projected record earn an excellent
 * detector score purely because its pixels agree with a coordinate nothing verified.
 *
 * **Recorded-vs-replayed residual** ([SkySessionFrameMetrics.replayMaxImageResidualPx],
 * [SkySessionFrameMetrics.replayRmsImageResidualPx],
 * [SkySessionFrameMetrics.replayClassificationMismatchCount]) is a *replay integrity* diagnostic: how
 * far the log's own record has drifted from what the math reproduces. It is never a detector residual
 * and must not be quoted as one. The two live in separate fields, and in separate columns, on purpose.
 *
 * ## The honesty gates, carried through
 * When replay skips a frame — `POSE_CLOCK_UNALIGNED` on a session whose camera never proved its
 * timestamp source, `OBSERVER_CONTEXT_UNAVAILABLE`, `MAGNETIC_DECLINATION_UNAVAILABLE`, or any other
 * categorized reason — there is no offline projection, so the projection-dependent metrics are absent,
 * not zero. No offset is invented, no substitute observer is assumed, no residual is emitted; the
 * reason is carried per frame and counted in the aggregate. SKY-1 forbids the fabrication and this is
 * where a loader would otherwise be tempted into it.
 *
 * A detector evaluation exists only when at least one replayed source is **observable in the analysis
 * image**. A replay-ready frame whose every source is behind the camera or off the raster has no truth
 * set, and a truth set of nothing is not one a detection rate or a false-positive count is defined
 * against — every detection would be counted spurious for want of anything to pair with. Such a frame
 * reports [SkyFrameEvaluationUnavailable.NO_OBSERVABLE_PREDICTIONS] and contributes only its pixel
 * detections, which stays distinct from a real truth set the detector scored zero against.
 *
 * As `DetectionEvaluation`'s own KDoc insists: these numbers say the detector recovered N of M *known*
 * sources at some residual. They are not a match rate, not a correspondence, and not evidence that
 * pointing works. This module builds no matcher and solves no pose.
 *
 * [DEFAULT_MATCH_TOLERANCE_PX] is the radius a detection is scored against a prediction within, in
 * analysis-buffer pixels.
 */
const val DEFAULT_MATCH_TOLERANCE_PX: Double = 2.0

/**
 * Why a frame carries no detector evaluation. Non-null exactly when
 * [SkySessionFrameMetrics.evaluation] is `null`, so "unavailable" is a state a reader can name rather
 * than a zero they have to interpret.
 *
 * When more than one applies, the reported one is the first of [PROJECTION_UNAVAILABLE],
 * [LUMA_UNAVAILABLE], [NO_OBSERVABLE_PREDICTIONS]. The underlying fields
 * ([SkySessionFrameMetrics.projectionSkipReason], [SkySessionFrameMetrics.lumaFailure]) stay populated
 * either way, so nothing is hidden by the ordering.
 */
enum class SkyFrameEvaluationUnavailable {
    /** The frame's luma file could not be read, so there was nothing to detect in. */
    LUMA_UNAVAILABLE,

    /**
     * Replay refused the frame, so there is no offline projection to score against. The categorized
     * reason is [SkySessionFrameMetrics.projectionSkipReason].
     */
    PROJECTION_UNAVAILABLE,

    /**
     * Replay succeeded, but not one recomputed source landed on the analysed raster — every one was
     * behind the camera or outside the image. Replay itself is fine, which is why this is not a
     * [SkyFrameReplaySkipReason]; there is simply nothing a detection rate could be measured against.
     */
    NO_OBSERVABLE_PREDICTIONS,
}

/** One frame's outcome: what was detected, and what could honestly be said about it. */
data class SkySessionFrameMetrics(
    val sequence: Long,
    val lumaPath: String,
    /** Sources found in this frame's pixels, or `null` when the pixels could not be read. */
    val detectedCount: Int?,
    /** Why the pixels are unavailable; `null` when they were read. */
    val lumaFailure: SkyLumaReadFailure? = null,
    val lumaFailureDetail: String? = null,
    /**
     * Detections scored against the **replayed** projection, or `null` when this frame has no truth
     * set. Never computed from the recorded pixel coordinates.
     */
    val evaluation: DetectionEvaluationReport? = null,
    /** Why there is no [evaluation]; `null` exactly when there is one. */
    val evaluationUnavailable: SkyFrameEvaluationUnavailable? = null,
    /** Why replay refused this frame; `null` when it replayed. */
    val projectionSkipReason: SkyFrameReplaySkipReason? = null,
    val projectionSkipDetail: String? = null,
    /**
     * Replay integrity, not detector error: the largest distance between a *recorded* predicted
     * position and the one replay *recomputed* for the same star, in analysis-buffer pixels. `null`
     * when replay skipped the frame or no star produced a comparable pair.
     */
    val replayMaxImageResidualPx: Double? = null,
    /** Replay integrity, not detector error: the RMS over the same set as [replayMaxImageResidualPx]. */
    val replayRmsImageResidualPx: Double? = null,
    /**
     * Replay integrity: how many stars replay placed in a different visibility class than the capture
     * recorded. `null` when replay skipped the frame.
     */
    val replayClassificationMismatchCount: Int? = null,
)

/** Every frame's outcome folded into one set of session-level numbers. */
data class SkySessionAggregateMetrics(
    val frameCount: Int,
    /** Frames whose luma file was read, and so had pixel detection run over them. */
    val framesWithPixels: Int,
    /** Point sources detected across [framesWithPixels]. Needs no pose; always honest. */
    val detectedSourceCount: Int,
    /**
     * Frames with a real evaluation — replayed, with pixels, and with at least one observable
     * recomputed source. Every metric below this line is drawn from these frames alone.
     */
    val scoredFrameCount: Int,
    /** Detector-observable, in-image **replayed** predictions across [scoredFrameCount]. */
    val predictedCount: Int,
    val matchedCount: Int,
    /** [matchedCount] over [predictedCount], or `null` when no frame carried a truth set. */
    val detectionRate: Double?,
    /** Pooled over every matched pair in the session, or `null` when nothing matched. */
    val centroidResidualRmsPx: Double?,
    val maxCentroidResidualPx: Double?,
    /**
     * Detections left unpaired on scored frames only. An upper bound on real spurious detections: the
     * predicted set is limited by catalogue depth, so a real star the catalogue did not carry counts
     * here. Frames with no truth set contribute nothing, since a false positive is undefined there.
     */
    val falsePositiveCount: Int,
    /**
     * Replay-ready frames where no recomputed source was observable in the image. They contribute to
     * [framesWithPixels] and [detectedSourceCount] and to nothing else.
     */
    val framesWithoutObservablePredictions: Int,
    val projectionSkipCounts: Map<SkyFrameReplaySkipReason, Int>,
    val lumaFailureCounts: Map<SkyLumaReadFailure, Int>,
    /** Replay integrity, not detector error: the worst recorded-vs-replayed residual in the session. */
    val maxReplayImageResidualPx: Double?,
    /** Replay integrity: total stars replay classified differently than the capture recorded. */
    val replayClassificationMismatchCount: Int,
)

/** One analyzed session. */
data class SkySessionDetectionReport(
    val sessionPath: String,
    val header: SkySessionLogHeader,
    val frames: List<SkySessionFrameMetrics>,
    /** Lines the codec could not read — a truncated final line costs one frame, not the session. */
    val unreadableLineCount: Int,
    /** Frames that appeared before the header line and so belong to no session. */
    val orphanFrameCount: Int,
    /** Lines carrying a schema version this build refuses to reinterpret. */
    val unsupportedSchemaCount: Int,
) {
    val aggregate: SkySessionAggregateMetrics by lazy { aggregate(frames) }
}

/** An analyzed session, or the categorized reason the directory could not be read. */
sealed interface SkySessionAnalysisResult {
    data class Ready(
        val report: SkySessionDetectionReport,
    ) : SkySessionAnalysisResult

    data class Failed(
        val reason: SkySessionLoadFailure,
        val detail: String? = null,
    ) : SkySessionAnalysisResult
}

/**
 * Loads [sessionDirectory] and runs detect -> evaluate over every frame in it.
 *
 * @param detectorConfig passed through to [detectStars] unchanged; the detector is not tuned here.
 * @param tolerancePx the pairing radius handed to [evaluateDetections].
 */
fun analyzeSkySession(
    sessionDirectory: File,
    detectorConfig: StarDetectorConfig = StarDetectorConfig(),
    tolerancePx: Double = DEFAULT_MATCH_TOLERANCE_PX,
): SkySessionAnalysisResult =
    when (val loaded = loadSkySessionLog(sessionDirectory)) {
        is SkySessionLoadResult.Failed -> SkySessionAnalysisResult.Failed(loaded.reason, loaded.detail)
        is SkySessionLoadResult.Loaded -> {
            val records = loaded.document.records
            // replaySkySessionLog maps records one-to-one, in order, so index is an exact pairing.
            // Matching on `sequence` would not be: nothing in the format forbids a duplicate sequence
            // number, and a map would then drop a frame that was really captured.
            val replay = replaySkySessionLog(loaded.header, records)
            SkySessionAnalysisResult.Ready(
                SkySessionDetectionReport(
                    sessionPath = sessionDirectory.path,
                    header = loaded.header,
                    frames =
                        records.mapIndexed { index, record ->
                            analyzeFrame(
                                sessionDirectory = sessionDirectory,
                                record = record,
                                replay = replay.frames[index],
                                detectorConfig = detectorConfig,
                                tolerancePx = tolerancePx,
                            )
                        },
                    unreadableLineCount = loaded.document.unreadable.size,
                    orphanFrameCount = loaded.document.orphanFrames.size,
                    unsupportedSchemaCount = loaded.document.unsupportedSchema.size,
                ),
            )
        }
    }

private fun analyzeFrame(
    sessionDirectory: File,
    record: SkyFrameRecord,
    replay: SkyFrameReplayResult,
    detectorConfig: StarDetectorConfig,
    tolerancePx: Double,
): SkySessionFrameMetrics {
    val luma = readSkyLumaFrame(sessionDirectory, record.luma)
    val detections =
        when (luma) {
            is SkyLumaReadResult.Loaded -> detectStars(luma.frame, detectorConfig)
            is SkyLumaReadResult.Failed -> null
        }
    val base =
        SkySessionFrameMetrics(
            sequence = record.sequence,
            lumaPath = record.luma.path,
            detectedCount = detections?.size,
            lumaFailure = (luma as? SkyLumaReadResult.Failed)?.reason,
            lumaFailureDetail = (luma as? SkyLumaReadResult.Failed)?.detail,
        )

    if (replay is SkyFrameReplayResult.Skipped) {
        return base.copy(
            evaluationUnavailable = SkyFrameEvaluationUnavailable.PROJECTION_UNAVAILABLE,
            projectionSkipReason = replay.reason,
            projectionSkipDetail = replay.geometryDetail,
        )
    }

    val ready = replay as SkyFrameReplayResult.Ready
    // The truth set is what the projection produces *now*, from this log's pose, observer and
    // intrinsics — not the coordinates the capture wrote down. See this file's KDoc.
    val truth: List<PredictedPointPx> = ready.projections.toPredictedPointsPx()
    val integrity =
        base.copy(
            replayMaxImageResidualPx = ready.maxImageResidualPx,
            replayRmsImageResidualPx = ready.rmsImageResidualPx,
            replayClassificationMismatchCount = ready.classificationMismatchCount,
        )
    return when {
        detections == null -> integrity.copy(evaluationUnavailable = SkyFrameEvaluationUnavailable.LUMA_UNAVAILABLE)
        // Scoring against an empty truth set would report every detection as a false positive and a
        // rate of nothing-over-nothing. Both are undefined here, so neither is produced.
        truth.isEmpty() ->
            integrity.copy(evaluationUnavailable = SkyFrameEvaluationUnavailable.NO_OBSERVABLE_PREDICTIONS)

        else -> integrity.copy(evaluation = evaluateDetections(detections, truth, tolerancePx))
    }
}

private fun aggregate(frames: List<SkySessionFrameMetrics>): SkySessionAggregateMetrics {
    val scored = frames.mapNotNull { it.evaluation }
    val predictedCount = scored.sumOf { it.predictedCount }
    val matchedCount = scored.sumOf { it.matchedCount }
    // Pooled over every matched pair rather than averaged over per-frame RMS values: a frame with two
    // matches would otherwise weigh as much as one with two hundred.
    val residuals = scored.flatMap { report -> report.matches.map { it.residualPx } }
    return SkySessionAggregateMetrics(
        frameCount = frames.size,
        framesWithPixels = frames.count { it.detectedCount != null },
        detectedSourceCount = frames.sumOf { it.detectedCount ?: 0 },
        scoredFrameCount = scored.size,
        predictedCount = predictedCount,
        matchedCount = matchedCount,
        detectionRate = if (predictedCount == 0) null else matchedCount.toDouble() / predictedCount,
        centroidResidualRmsPx = residuals.rootMeanSquareOrNull(),
        maxCentroidResidualPx = residuals.maxOrNull(),
        falsePositiveCount = scored.sumOf { it.falsePositiveCount },
        framesWithoutObservablePredictions =
            frames.count { it.evaluationUnavailable == SkyFrameEvaluationUnavailable.NO_OBSERVABLE_PREDICTIONS },
        projectionSkipCounts =
            frames
                .mapNotNull { it.projectionSkipReason }
                .groupingBy { it }
                .eachCount(),
        lumaFailureCounts =
            frames
                .mapNotNull { it.lumaFailure }
                .groupingBy { it }
                .eachCount(),
        maxReplayImageResidualPx = frames.mapNotNull { it.replayMaxImageResidualPx }.maxOrNull(),
        replayClassificationMismatchCount = frames.sumOf { it.replayClassificationMismatchCount ?: 0 },
    )
}

private fun List<Double>.rootMeanSquareOrNull(): Double? {
    if (isEmpty()) return null
    return sqrt(sumOf { it * it } / size)
}
