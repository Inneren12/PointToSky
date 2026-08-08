package dev.pointtosky.core.astro.projection.camera.skylog

import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import dev.pointtosky.core.astro.projection.camera.PixelPoint
import dev.pointtosky.core.astro.projection.camera.createCameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.pairFrameToNearestRotation
import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarClassification
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarProjection
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionBatchResult
import dev.pointtosky.core.astro.projection.camera.prediction.StarProjectionContext
import dev.pointtosky.core.astro.projection.camera.prediction.projectStars
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * SKY-1 replay: re-runs the existing pure projection math over a recorded session log, with no
 * device, no camera, and no Android dependency of any kind.
 *
 * This is the half of the capture mechanism that makes the other half worth having. Capturing frames
 * is only useful if the same numbers can be recomputed offline and compared; that loop — load a log,
 * run, compare — is the foundation a star detector gets developed against.
 *
 * ## What it does and does not re-derive
 * Replay rebuilds exactly the inputs the device had — `CameraFrameMetadata`, the paired
 * `TimedRotationSample`, `CameraIntrinsicsResolution`, the viewport — and runs the *same*
 * [createCameraSessionGeometry] and [projectStars] the device ran, at the *same* pairing tolerances
 * ([SkySessionLogHeader.maxPairDeltaNanos]/[SkySessionLogHeader.clockMismatchThresholdNanos]). It
 * reimplements none of that math and substitutes no defaults, so a frame the device rejected replays
 * as rejected, for the same categorized reason.
 *
 * The one thing it does not reuse is the *result*: [SkyPredictedStar]'s recorded pixel coordinates
 * are treated purely as the expected value to diff against, never as an input. That is what makes a
 * replay a check rather than an echo.
 */

/** Why one recorded frame could not be replayed. Every value is a real, expected capture condition. */
enum class SkyFrameReplaySkipReason {
    /** No location/time was available when the frame was captured, so there is no observing context. */
    OBSERVER_CONTEXT_UNAVAILABLE,

    /**
     * The magnetic declination was unavailable at capture time. Never silently replaced by `0.0` —
     * see `StarProjectionContext`'s KDoc on why "uncorrected" and "declination is zero" differ.
     */
    MAGNETIC_DECLINATION_UNAVAILABLE,

    /**
     * The pose timestamp cannot be expressed on the frame clock: the session recorded two different
     * clocks with no measured offset between them, or one of them as [SkyClock.UNKNOWN], or the
     * aligned value fell outside the non-negative range a `TimedRotationSample` accepts.
     */
    POSE_CLOCK_UNALIGNED,

    /** The recorded viewport was not a usable size (`CameraSessionGeometryResult.InvalidViewport`). */
    INVALID_VIEWPORT,

    /** The pose was too far from the frame in time at the session's own tolerance. */
    ROTATION_UNAVAILABLE,

    /** `createCameraSessionGeometry` rejected the reconstructed bundle; see [geometryDetail]. */
    GEOMETRY_REJECTED,

    /**
     * `projectStars` refused the whole batch — the recorded intrinsics are not analysis-buffer
     * referenced, or their reference dimensions do not match this frame's buffer.
     */
    INTRINSICS_MAPPING_UNAVAILABLE,
}

/**
 * One recorded star's recomputed-vs-recorded comparison.
 *
 * [imageResidualPx] is the Euclidean distance in **analysis-buffer pixels** — the same space the
 * luma file is stored in, and therefore the number a detector's own error is directly comparable to.
 * It is `null` when either side has no image point (a star behind the camera), which is a normal
 * outcome and not a zero residual.
 */
data class SkyPredictedStarResidual(
    val catalogIndex: Int,
    val recordedClassification: PredictedStarClassification,
    val replayedClassification: PredictedStarClassification,
    val imageResidualPx: Double? = null,
    val displayResidualPx: Double? = null,
) {
    /** Whether replay put this star in a different visibility class than the capture did. */
    val classificationMatches: Boolean get() = recordedClassification == replayedClassification
}

/** The outcome of replaying one recorded frame. */
sealed interface SkyFrameReplayResult {
    val sequence: Long

    /**
     * @property projections the freshly computed projections, in recorded-star order (which
     *   [projectStars] preserves).
     * @property residuals one entry per recorded star, in the same order.
     * @property maxImageResidualPx the largest finite image-space residual, or `null` when no star
     *   produced a comparable pair.
     * @property rmsImageResidualPx root-mean-square over the same set as [maxImageResidualPx].
     */
    data class Ready(
        override val sequence: Long,
        val geometry: CameraSessionGeometry,
        val projections: List<PredictedStarProjection>,
        val residuals: List<SkyPredictedStarResidual>,
        val maxImageResidualPx: Double?,
        val rmsImageResidualPx: Double?,
    ) : SkyFrameReplayResult {
        /** How many stars replay classified differently than the recorded capture did. */
        val classificationMismatchCount: Int get() = residuals.count { !it.classificationMatches }
    }

    /**
     * @property geometryDetail the underlying categorized reason name when one exists (e.g. a
     *   `GeometryRejectionReason` or `IntrinsicsMappingUnavailableReason`), otherwise `null`. Always
     *   an enum name, never a `toString()` of a whole result object.
     */
    data class Skipped(
        override val sequence: Long,
        val reason: SkyFrameReplaySkipReason,
        val geometryDetail: String? = null,
    ) : SkyFrameReplayResult
}

/** A whole replayed log. */
data class SkySessionReplayReport(
    val header: SkySessionLogHeader,
    val frames: List<SkyFrameReplayResult>,
) {
    val readyFrames: List<SkyFrameReplayResult.Ready> get() = frames.filterIsInstance<SkyFrameReplayResult.Ready>()

    val skippedFrames: List<SkyFrameReplayResult.Skipped> get() =
        frames
            .filterIsInstance<SkyFrameReplayResult.Skipped>()

    /** The largest image-space residual across every replayed frame, or `null` when none produced one. */
    val maxImageResidualPx: Double? get() = readyFrames.mapNotNull { it.maxImageResidualPx }.maxOrNull()
}

/** The observing context this record implies, or `null` when it does not carry one. */
fun SkyObserverContext.toStarProjectionContext(): StarProjectionContext? {
    val declinationDeg = magneticDeclinationDeg ?: return null
    return StarProjectionContext.of(
        latitudeRad = Math.toRadians(latitudeDeg),
        longitudeRad = Math.toRadians(longitudeDeg),
        utcEpochMillis = utcEpochMillis,
        magneticDeclinationRad = Math.toRadians(declinationDeg),
    )
}

/**
 * Rebuilds the exact `CameraSessionGeometry` the device had for [record], using [header]'s own
 * recorded pairing tolerances. Returns the categorized [CameraSessionGeometryResult] unchanged —
 * callers that only want a replay should use [replaySkySessionFrame]; this is exposed separately for
 * offline tools that want the geometry without the projection.
 *
 * Returns `null` when the pose timestamp cannot be placed on the frame clock at all, which is a
 * log-integrity condition rather than a geometry outcome and so has no [CameraSessionGeometryResult]
 * to express it.
 */
fun rebuildSkyFrameGeometry(
    header: SkySessionLogHeader,
    record: SkyFrameRecord,
): CameraSessionGeometryResult? {
    val alignedPoseNanos =
        alignPoseTimestampToFrameClock(record.pose.timestampNanos, header.clockAlignment)
            ?.takeIf { it >= 0L }
            ?: return null
    val pairing =
        pairFrameToNearestRotation(
            frame = record.frame,
            samples = listOf(record.pose.toTimedRotationSample(alignedPoseNanos)),
            maxAllowedDeltaNanos = header.maxPairDeltaNanos,
            clockMismatchThresholdNanos = header.clockMismatchThresholdNanos,
        )
    return createCameraSessionGeometry(
        frame = record.frame,
        pairingResult = pairing,
        intrinsicsResolution = header.intrinsics.toCameraIntrinsicsResolution(),
        viewportWidthPx = record.viewportWidthPx,
        viewportHeightPx = record.viewportHeightPx,
        maxAllowedPairDeltaNanos = header.maxPairDeltaNanos,
    )
}

/** Replays one recorded frame: rebuild the geometry, re-project the recorded stars, diff. */
fun replaySkySessionFrame(
    header: SkySessionLogHeader,
    record: SkyFrameRecord,
): SkyFrameReplayResult {
    val observer =
        record.observer
            ?: return SkyFrameReplayResult.Skipped(
                record.sequence,
                SkyFrameReplaySkipReason.OBSERVER_CONTEXT_UNAVAILABLE,
            )
    val context =
        observer.toStarProjectionContext()
            ?: return SkyFrameReplayResult.Skipped(
                record.sequence,
                SkyFrameReplaySkipReason.MAGNETIC_DECLINATION_UNAVAILABLE,
            )

    val geometryResult =
        rebuildSkyFrameGeometry(header, record)
            ?: return SkyFrameReplayResult.Skipped(record.sequence, SkyFrameReplaySkipReason.POSE_CLOCK_UNALIGNED)

    val geometry =
        when (geometryResult) {
            is CameraSessionGeometryResult.Ready -> geometryResult.geometry
            is CameraSessionGeometryResult.InvalidViewport ->
                return SkyFrameReplayResult.Skipped(record.sequence, SkyFrameReplaySkipReason.INVALID_VIEWPORT)

            is CameraSessionGeometryResult.RotationUnavailable ->
                return SkyFrameReplayResult.Skipped(
                    record.sequence,
                    SkyFrameReplaySkipReason.ROTATION_UNAVAILABLE,
                    geometryResult.reason.name,
                )

            is CameraSessionGeometryResult.GeometryRejected ->
                return SkyFrameReplayResult.Skipped(
                    record.sequence,
                    SkyFrameReplaySkipReason.GEOMETRY_REJECTED,
                    geometryResult.reason.name,
                )

            // Reconstructed bundles are always built with a concrete frame, a concrete intrinsics
            // resolution, and no live session, so these three outcomes are unreachable from
            // createCameraSessionGeometry - they exist for the live provider, not this path. Mapped
            // rather than ignored so a future variant cannot silently fall through as "ready".
            is CameraSessionGeometryResult.MissingFrame,
            is CameraSessionGeometryResult.IntrinsicsUnavailable,
            is CameraSessionGeometryResult.Disposed,
            ->
                return SkyFrameReplayResult.Skipped(
                    record.sequence,
                    SkyFrameReplaySkipReason.GEOMETRY_REJECTED,
                    geometryResult::class.simpleName,
                )
        }

    val directions =
        record.predictedStars.map { star ->
            EquatorialStarDirection.of(
                catalogIndex = star.catalogIndex,
                rightAscensionRad = star.rightAscensionRad,
                declinationRad = star.declinationRad,
                magnitude = star.magnitude,
            )
        }

    val projections =
        when (val batch = projectStars(stars = directions, context = context, geometry = geometry)) {
            is StarPredictionBatchResult.Ready -> batch.projections
            is StarPredictionBatchResult.IntrinsicsMappingUnavailable ->
                return SkyFrameReplayResult.Skipped(
                    record.sequence,
                    SkyFrameReplaySkipReason.INTRINSICS_MAPPING_UNAVAILABLE,
                    batch.reason.name,
                )
        }

    val residuals = record.predictedStars.zip(projections, ::residualOf)
    val imageResiduals = residuals.mapNotNull { it.imageResidualPx }
    return SkyFrameReplayResult.Ready(
        sequence = record.sequence,
        geometry = geometry,
        projections = projections,
        residuals = residuals,
        maxImageResidualPx = imageResiduals.maxOrNull(),
        rmsImageResidualPx = imageResiduals.rootMeanSquare(),
    )
}

/** Replays every frame in [document]. Returns `null` when the log has no readable header to replay against. */
fun replaySkySessionLog(document: SkySessionLogDocument): SkySessionReplayReport? {
    val header = document.header ?: return null
    return replaySkySessionLog(header, document.records)
}

/** Replays [records] against [header]. */
fun replaySkySessionLog(
    header: SkySessionLogHeader,
    records: List<SkyFrameRecord>,
): SkySessionReplayReport =
    SkySessionReplayReport(
        header = header,
        frames = records.map { replaySkySessionFrame(header, it) },
    )

private fun residualOf(
    recorded: SkyPredictedStar,
    replayed: PredictedStarProjection,
): SkyPredictedStarResidual =
    SkyPredictedStarResidual(
        catalogIndex = recorded.catalogIndex,
        recordedClassification = recorded.classification,
        replayedClassification = replayed.classification,
        imageResidualPx = distanceOrNull(recorded.imageXPx, recorded.imageYPx, replayed.imagePoint),
        displayResidualPx = distanceOrNull(recorded.displayXPx, recorded.displayYPx, replayed.displayPoint),
    )

private fun distanceOrNull(
    recordedX: Double?,
    recordedY: Double?,
    replayed: PixelPoint?,
): Double? {
    if (recordedX == null || recordedY == null || replayed == null) return null
    return hypot(replayed.x - recordedX, replayed.y - recordedY)
}

private fun List<Double>.rootMeanSquare(): Double? {
    if (isEmpty()) return null
    return sqrt(sumOf { it * it } / size)
}
