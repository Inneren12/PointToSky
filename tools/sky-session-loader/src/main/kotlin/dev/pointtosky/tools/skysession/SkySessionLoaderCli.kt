package dev.pointtosky.tools.skysession

import dev.pointtosky.core.astro.projection.camera.detect.StarDetectorConfig
import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogHeader
import java.io.File
import java.util.Locale
import kotlin.system.exitProcess

/**
 * SKY-3 CLI: point it at a captured session directory and it prints what the detector found in it.
 *
 * ```text
 * ./gradlew :tools:sky-session-loader:installDist
 * tools/sky-session-loader/build/install/sky-session-loader/bin/sky-session-loader <session-dir>
 * ```
 *
 * Every number printed comes from [analyzeSkySession] and therefore from `:core:astro-core`. Three
 * kinds of column are kept apart on purpose, per frame and in the aggregate:
 *
 *  - `detected` is what the **pixels** contain. It needs no pose and is printed for every frame whose
 *    luma could be read, including frames replay refused.
 *  - `rate` / `rmsPx` / `maxPx` / `falsePos` score the detector against the **replayed** projection —
 *    the truth set the offline math reproduces, never the coordinates the capture recorded. They exist
 *    only for a frame that replayed *and* has at least one recomputed source observable in the image.
 *  - `rplMaxPx` is **replay integrity**, not detector error: how far the recorded coordinates have
 *    drifted from the recomputed ones. A large value there with a small `rmsPx` means the log's record
 *    is stale, not that the detector is wrong.
 *
 * A frame with no truth set prints its reason and dashes — never a zero, which would read as "the
 * detector found nothing" rather than "there was nothing to score against".
 */

private const val TOLERANCE_FLAG = "--tolerance-px="
private const val SIGMA_FLAG = "--sigma="
private const val QUIET_FLAG = "--quiet"

private const val EXIT_FAILURE = 1

private data class CliConfig(
    val sessionDirectory: File,
    val tolerancePx: Double,
    val detectorConfig: StarDetectorConfig,
    val perFrame: Boolean,
)

fun main(args: Array<String>) {
    val config =
        try {
            parseArgs(args)
        } catch (error: IllegalArgumentException) {
            System.err.println(error.message)
            printUsage()
            exitProcess(EXIT_FAILURE)
        }

    when (val result = analyzeSkySession(config.sessionDirectory, config.detectorConfig, config.tolerancePx)) {
        is SkySessionAnalysisResult.Failed -> {
            System.err.println("cannot read session: ${result.reason}${result.detail?.let { " ($it)" } ?: ""}")
            exitProcess(EXIT_FAILURE)
        }

        is SkySessionAnalysisResult.Ready -> printReport(result.report, config.perFrame)
    }
}

private fun parseArgs(args: Array<String>): CliConfig {
    var sessionDirectory: File? = null
    var tolerancePx = DEFAULT_MATCH_TOLERANCE_PX
    var sigmaThreshold: Double? = null
    var perFrame = true

    for (arg in args) {
        when {
            arg.startsWith(TOLERANCE_FLAG) -> tolerancePx = arg.removePrefix(TOLERANCE_FLAG).toDoubleOrThrow(arg)
            arg.startsWith(SIGMA_FLAG) -> sigmaThreshold = arg.removePrefix(SIGMA_FLAG).toDoubleOrThrow(arg)
            arg == QUIET_FLAG -> perFrame = false
            arg.startsWith("-") -> usageError("Unknown option: $arg")
            sessionDirectory != null -> usageError("Only one session directory may be given")
            else -> sessionDirectory = File(arg)
        }
    }

    val directory = sessionDirectory ?: usageError("No session directory given")
    // StarDetectorConfig validates its own bounds; the require here is only so a bad flag reports as a
    // usage error rather than as a stack trace from inside the detector.
    require(tolerancePx > 0.0 && tolerancePx.isFinite()) { "tolerance-px must be positive and finite" }
    require(sigmaThreshold == null || (sigmaThreshold > 0.0 && sigmaThreshold.isFinite())) {
        "sigma must be positive and finite"
    }
    return CliConfig(
        sessionDirectory = directory,
        tolerancePx = tolerancePx,
        detectorConfig =
            sigmaThreshold?.let { StarDetectorConfig(sigmaThreshold = it) } ?: StarDetectorConfig(),
        perFrame = perFrame,
    )
}

private fun String.toDoubleOrThrow(arg: String): Double = toDoubleOrNull() ?: usageError("Not a number: $arg")

/** Every bad-argument path funnels through here, so `main` has exactly one way to report usage. */
private fun usageError(message: String): Nothing = throw IllegalArgumentException(message)

private fun printUsage() {
    println(
        """
        Usage: sky-session-loader <session-dir> [options]

          <session-dir>        a SKY-1 capture directory: session.jsonl plus frames/frame_NNNNNN.y

        Options:
          --tolerance-px=N     detection-to-prediction pairing radius, in analysis-buffer pixels
                               (default $DEFAULT_MATCH_TOLERANCE_PX)
          --sigma=N            detector threshold in sigmas above the local background
          --quiet              print the aggregate only, without the per-frame table
        """.trimIndent(),
    )
}

private fun printReport(
    report: SkySessionDetectionReport,
    perFrame: Boolean,
) {
    printHeader(report)
    if (perFrame) {
        println()
        println(
            String.format(
                Locale.ROOT,
                FRAME_FORMAT,
                "seq",
                "detected",
                "predicted",
                "matched",
                "rate",
                "rmsPx",
                "maxPx",
                "falsePos",
                "rplMaxPx",
                "note",
            ),
        )
        report.frames.forEach { println(formatFrame(it)) }
        println(
            "  (predicted/matched/rate/rmsPx/maxPx/falsePos score the detector against the replayed " +
                "projection; rplMaxPx is the recorded-vs-replayed integrity residual, not detector error)",
        )
    }
    println()
    printAggregate(report.aggregate)
}

private fun printHeader(report: SkySessionDetectionReport) {
    val header = report.header
    println("session:   ${report.sessionPath}")
    println("id:        ${header.sessionId}  schema v${header.schemaVersion}")
    println(
        "device:    ${header.deviceModel ?: "-"}  camera ${header.cameraId ?: "-"}  " +
            "buffer ${header.bufferWidthPx}x${header.bufferHeightPx}  luma ${header.lumaFormat}",
    )
    println("clock:     ${describeClock(header)}")
    if (report.unreadableLineCount > 0 || report.orphanFrameCount > 0 || report.unsupportedSchemaCount > 0) {
        println(
            "log:       ${report.unreadableLineCount} unreadable line(s), " +
                "${report.orphanFrameCount} pre-header frame(s), " +
                "${report.unsupportedSchemaCount} unsupported-schema line(s)",
        )
    }
}

/**
 * The clock claim as the log itself states it. Printed prominently because it is what decides whether
 * any frame in this session can carry a projection metric at all.
 */
private fun describeClock(header: SkySessionLogHeader): String {
    val alignment = header.clockAlignment
    val offset = alignment.poseToFrameOffsetNanos?.let { " offset ${it}ns" } ?: ""
    return "${alignment.relationship} (frame ${alignment.frameClock}, pose ${alignment.poseClock})$offset"
}

private fun formatFrame(frame: SkySessionFrameMetrics): String {
    val evaluation = frame.evaluation
    return String.format(
        Locale.ROOT,
        FRAME_FORMAT,
        frame.sequence.toString(),
        frame.detectedCount?.toString() ?: "-",
        evaluation?.predictedCount?.toString() ?: "-",
        evaluation?.matchedCount?.toString() ?: "-",
        formatRatio(evaluation?.detectionRate),
        formatPixels(evaluation?.centroidResidualRmsPx),
        formatPixels(evaluation?.maxCentroidResidualPx),
        evaluation?.falsePositiveCount?.toString() ?: "-",
        formatPixels(frame.replayMaxImageResidualPx),
        describeFrameState(frame),
    )
}

/**
 * The per-frame note. Every applicable fact is printed rather than only the first, so a frame that is
 * missing its pixels *and* was refused by replay says both.
 */
private fun describeFrameState(frame: SkySessionFrameMetrics): String {
    val notes = mutableListOf<String>()
    frame.lumaFailure?.let { failure ->
        notes += "pixels unavailable: $failure${frame.lumaFailureDetail?.let { " ($it)" } ?: ""}"
    }
    frame.projectionSkipReason?.let { reason ->
        notes += "projection skipped: $reason${frame.projectionSkipDetail?.let { " ($it)" } ?: ""}"
    }
    if (frame.evaluationUnavailable == SkyFrameEvaluationUnavailable.NO_OBSERVABLE_PREDICTIONS) {
        notes += "no truth set: replay placed no source in the analysis image"
    }
    if (frame.replayClassificationMismatchCount?.takeIf { it > 0 } != null) {
        notes += "replay integrity: ${frame.replayClassificationMismatchCount} classification mismatch(es)"
    }
    return notes.joinToString(separator = "; ")
}

private fun printAggregate(aggregate: SkySessionAggregateMetrics) {
    println("aggregate (pixels)")
    println("  frames in log            ${aggregate.frameCount}")
    println("  frames with pixels       ${aggregate.framesWithPixels}")
    println("  detected sources         ${aggregate.detectedSourceCount}")
    println("aggregate (detector vs replayed projection)")
    println("  frames scored            ${aggregate.scoredFrameCount}")
    println("  predictions scored       ${aggregate.predictedCount}")
    println("  matched                  ${aggregate.matchedCount}")
    println("  detection rate           ${formatRatio(aggregate.detectionRate)}")
    println("  centroid residual RMS    ${formatPixels(aggregate.centroidResidualRmsPx)} px")
    println("  centroid residual max    ${formatPixels(aggregate.maxCentroidResidualPx)} px")
    println("  false positives          ${aggregate.falsePositiveCount}")
    // Printed text stays ASCII: a terminal whose stdout charset is not UTF-8 turns a dash into a "?".
    println("aggregate (replay integrity, recorded vs replayed, not detector error)")
    println("  max image residual       ${formatPixels(aggregate.maxReplayImageResidualPx)} px")
    println("  classification mismatch  ${aggregate.replayClassificationMismatchCount}")
    if (aggregate.framesWithoutObservablePredictions > 0) {
        println(
            "  frames with no truth set (replayed, but no source in the analysis image): " +
                "${aggregate.framesWithoutObservablePredictions}",
        )
    }
    if (aggregate.projectionSkipCounts.isNotEmpty()) {
        println("  frames replay refused (no offline projection, so no truth set):")
        aggregate.projectionSkipCounts.entries
            .sortedBy { it.key.name }
            .forEach { (reason, count) -> println("    $reason: $count") }
    }
    if (aggregate.lumaFailureCounts.isNotEmpty()) {
        println("  frames whose pixels could not be read:")
        aggregate.lumaFailureCounts.entries
            .sortedBy { it.key.name }
            .forEach { (reason, count) -> println("    $reason: $count") }
    }
}

private fun formatRatio(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.3f", it) } ?: "-"

private fun formatPixels(value: Double?): String = value?.let { String.format(Locale.ROOT, "%.4f", it) } ?: "-"

private const val FRAME_FORMAT = "%-8s %-9s %-10s %-8s %-7s %-8s %-8s %-9s %-9s %s"
