package dev.pointtosky.tools.skysession

import dev.pointtosky.core.astro.projection.camera.detect.DetectionEvaluationReport
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
 * [replaySkySessionLog] decides whether a frame's projection can be trusted offline, and
 * [evaluateDetections] scores one against the other. This file only sequences them and counts.
 *
 * ## Two kinds of number, never mixed
 * A frame yields **pure pixel detection** — how many point sources are in its luma — from the pixels
 * alone. It needs no pose, no observer and no clock: [SkySessionFrameMetrics.detectedCount] is
 * reported for every frame whose file could be read, including frames replay refused.
 *
 * It yields a **detection rate and a centroid residual** only when there is a truth set to score
 * against, and that requires the frame's recorded predictions to be ones replay could reproduce. When
 * replay skips the frame — `POSE_CLOCK_UNALIGNED` on a session whose camera never proved its timestamp
 * source, `OBSERVER_CONTEXT_UNAVAILABLE`, `MAGNETIC_DECLINATION_UNAVAILABLE`, or any other categorized
 * reason — the projection-dependent metrics are absent, not zero. No offset is invented, no substitute
 * observer is assumed, and no residual is emitted; the reason is carried per frame instead
 * ([SkySessionFrameMetrics.projectionSkipReason]) and counted in the aggregate. SKY-1 forbids the
 * fabrication and this is where a loader would otherwise be tempted into it.
 *
 * Gating on *any* replay skip rather than only the three clock/observer ones is deliberate: every
 * reason in [SkyFrameReplaySkipReason] means the recorded pixel positions could not be re-derived from
 * the log, and a truth set that cannot be re-derived is not a truth set.
 *
 * ## What the truth set is
 * The recorded `SkyPredictedStar` positions, narrowed by [toPredictedPointsPx] to the ones that landed
 * on the analysed raster. Reading the recorded coordinates is correct precisely because replay has
 * already re-derived them independently and diffed the two — that diff is carried as
 * [SkySessionFrameMetrics.replayMaxImageResidualPx] so a frame whose recorded and recomputed positions
 * disagree is visible rather than silently scored against.
 *
 * As `DetectionEvaluation`'s own KDoc insists: these numbers say the detector recovered N of M *known*
 * sources at some residual. They are not a match rate, not a correspondence, and not evidence that
 * pointing works. This module builds no matcher and solves no pose.
 *
 * [DEFAULT_MATCH_TOLERANCE_PX] is the radius a detection is scored against a prediction within, in
 * analysis-buffer pixels.
 */
const val DEFAULT_MATCH_TOLERANCE_PX: Double = 2.0

/** One frame's outcome: what was detected, and what could honestly be said about it. */
data class SkySessionFrameMetrics(
    val sequence: Long,
    val lumaPath: String,
    /** Sources found in this frame's pixels, or `null` when the pixels could not be read. */
    val detectedCount: Int?,
    /** Why the pixels are unavailable; `null` when they were read. */
    val lumaFailure: SkyLumaReadFailure? = null,
    val lumaFailureDetail: String? = null,
    /** The scored comparison against this frame's predictions, or `null` when there may not be one. */
    val evaluation: DetectionEvaluationReport? = null,
    /** Why this frame carries no projection-dependent metrics; `null` when it does. */
    val projectionSkipReason: SkyFrameReplaySkipReason? = null,
    val projectionSkipDetail: String? = null,
    /**
     * The largest distance between a recorded predicted position and the one replay recomputed for the
     * same star, in analysis-buffer pixels. This is the projection's own self-check, not the detector's
     * error; `null` when replay skipped the frame or no star produced a comparable pair.
     */
    val replayMaxImageResidualPx: Double? = null,
)

/** Every frame's outcome folded into one set of session-level numbers. */
data class SkySessionAggregateMetrics(
    val frameCount: Int,
    /** Frames whose luma file was read, and so had pixel detection run over them. */
    val framesWithPixels: Int,
    /** Point sources detected across [framesWithPixels]. Needs no pose; always honest. */
    val detectedSourceCount: Int,
    /** Frames that carry projection-dependent metrics — those replay could reproduce. */
    val scoredFrameCount: Int,
    /** Detector-observable, in-image predictions across [scoredFrameCount]. */
    val predictedCount: Int,
    val matchedCount: Int,
    /** [matchedCount] over [predictedCount], or `null` when nothing observable was predicted. */
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
    val projectionSkipCounts: Map<SkyFrameReplaySkipReason, Int>,
    val lumaFailureCounts: Map<SkyLumaReadFailure, Int>,
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

    return when (replay) {
        is SkyFrameReplayResult.Skipped ->
            base.copy(projectionSkipReason = replay.reason, projectionSkipDetail = replay.geometryDetail)

        is SkyFrameReplayResult.Ready ->
            base.copy(
                // Without pixels there is nothing to score; the predictions are usable, the frame is not.
                evaluation =
                    detections?.let {
                        evaluateDetections(it, record.predictedStars.toPredictedPointsPx(), tolerancePx)
                    },
                replayMaxImageResidualPx = replay.maxImageResidualPx,
            )
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
    )
}

private fun List<Double>.rootMeanSquareOrNull(): Double? {
    if (isEmpty()) return null
    return sqrt(sumOf { it * it } / size)
}
