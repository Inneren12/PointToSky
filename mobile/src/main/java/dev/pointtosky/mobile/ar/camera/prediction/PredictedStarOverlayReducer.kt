package dev.pointtosky.mobile.ar.camera.prediction

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult
import dev.pointtosky.core.astro.projection.camera.prediction.EquatorialStarDirection
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarClassification
import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarProjection
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionBatchResult
import dev.pointtosky.core.astro.projection.camera.prediction.StarProjectionContext
import dev.pointtosky.core.astro.projection.camera.prediction.projectStars
import dev.pointtosky.core.astro.projection.camera.prediction.summarizeStarPredictions
import dev.pointtosky.mobile.ar.camera.toDiagnosticCategory

/**
 * CAM-2b pure reducer (task §5/§6): builds one [PredictedStarOverlayState] from the current
 * camera-geometry observation, observer inputs, and bounded star subset. Called from Compose
 * memoization (never from an uncontrolled drawing-loop call, never from a permanently launched
 * coroutine) — see `ArScreen.kt`'s call site for the `remember` keys.
 *
 * ## Ownership of true-north correction (task §4)
 * [geometryResult] must always carry the **raw**, magnetic-north-referenced paired rotation exactly as
 * published by `CameraSessionGeometryProvider` — never a matrix already corrected via
 * `RotationFrame.correctedForTrueNorth`. This function is the *only* place [magneticDeclinationDeg] is
 * converted to radians and passed into [StarProjectionContext.magneticDeclinationRad]; CAM-2a's own
 * `projectStars` performs the one true-north correction (`trueEnuToMagneticEnu`) internally, using
 * exactly that context value against exactly that raw matrix. Applying `correctedForTrueNorth` to the
 * geometry's rotation matrix *and* passing a non-zero declination here would double-correct true north
 * — callers must never do both. See `docs/camera_star_prediction_contract.md` §4.6.
 *
 * ## Prerequisites (task §6)
 * Projection only runs once every one of the following is coherent, in this order:
 *  1. [gateEnabled] — the `internalDebug`-only build gate.
 *  2. [geometryResult] is [CameraSessionGeometryResult.Ready].
 *  3. [observerLatitudeDeg]/[observerLongitudeDeg] are non-null and finite (mirrors
 *     `ArUiState.Ready.locationResolved`).
 *  4. [utcEpochMillis] is non-null (a coherent observation instant is available).
 *  5. [magneticDeclinationDeg] is non-null and finite — never silently defaulted to `0.0`.
 *  6. [stars] is non-empty.
 *
 * Any failed prerequisite maps to a specific [PredictedStarOverlayWaitingReason] — never a generic
 * "loading" state and never silently-drawn-nothing.
 */
fun reducePredictedStarOverlayState(
    gateEnabled: Boolean,
    geometryResult: CameraSessionGeometryResult,
    observerLatitudeDeg: Double?,
    observerLongitudeDeg: Double?,
    utcEpochMillis: Long?,
    magneticDeclinationDeg: Double?,
    stars: List<EquatorialStarDirection>,
): PredictedStarOverlayState {
    if (!gateEnabled) return PredictedStarOverlayState.Disabled

    if (geometryResult !is CameraSessionGeometryResult.Ready) {
        return PredictedStarOverlayState.Waiting(
            PredictedStarOverlayWaitingReason.GeometryNotReady(geometryResult.toDiagnosticCategory()),
        )
    }

    if (observerLatitudeDeg == null || observerLongitudeDeg == null ||
        !observerLatitudeDeg.isFinite() || !observerLongitudeDeg.isFinite()
    ) {
        return PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.ObserverLocationUnavailable)
    }

    if (utcEpochMillis == null) {
        return PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.ObservationTimeUnavailable)
    }

    if (magneticDeclinationDeg == null || !magneticDeclinationDeg.isFinite()) {
        return PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.MagneticDeclinationUnavailable)
    }

    if (stars.isEmpty()) {
        return PredictedStarOverlayState.Waiting(PredictedStarOverlayWaitingReason.NoStarsSelected)
    }

    val context =
        StarProjectionContext.of(
            latitudeRad = Math.toRadians(observerLatitudeDeg),
            longitudeRad = Math.toRadians(observerLongitudeDeg),
            utcEpochMillis = utcEpochMillis,
            magneticDeclinationRad = Math.toRadians(magneticDeclinationDeg),
        )

    return when (val batch = projectStars(stars = stars, context = context, geometry = geometryResult.geometry)) {
        is StarPredictionBatchResult.IntrinsicsMappingUnavailable -> PredictedStarOverlayState.Unavailable(batch.reason)

        is StarPredictionBatchResult.Ready -> {
            val summary = summarizeStarPredictions(batch.projections)
            val points = toOverlayPoints(batch.projections)
            val geometry = geometryResult.geometry
            val metadata =
                PredictedStarOverlayMetadata(
                    inputCount = summary.inputCount,
                    visibleCount = summary.visibleInViewportCount,
                    frameTimestampNanos = geometry.frame.timestampNanos,
                    rotationTimestampNanos = geometry.pairedRotation.timestampNanos,
                    pairDeltaNanos = geometry.frameRotationDeltaNanos,
                    frameRotationDegrees = geometry.frame.rotationDegrees,
                    intrinsicsSource = geometry.intrinsics.intrinsics.source.name,
                    intrinsicsReference = describeIntrinsicsReference(geometry.intrinsics.intrinsics.reference),
                    magneticDeclinationDeg = magneticDeclinationDeg,
                )
            PredictedStarOverlayState.Ready(points = points, summary = summary, metadata = metadata)
        }
    }
}

/**
 * CAM-2b task §8/§12.D/§12.E/§12.F: the pure mapping from a full CAM-2a batch to drawable overlay
 * points. Only [PredictedStarClassification.VISIBLE_IN_VIEWPORT] projections become points — never
 * `BEHIND_CAMERA`/`OUTSIDE_IMAGE`/`INSIDE_IMAGE_OUTSIDE_VIEWPORT` — and [PredictedStarProjection.displayPoint]
 * is copied verbatim into [PredictedStarOverlayPoint.displayX]/[PredictedStarOverlayPoint.displayY]: no
 * additional scale, rotation, crop offset, or rounding. `filter` then `map` preserves [projections]'
 * own input/result order (CAM-2a never reorders its input; see `projectStars`'s own contract).
 */
internal fun toOverlayPoints(projections: List<PredictedStarProjection>): List<PredictedStarOverlayPoint> =
    projections
        .filter { it.classification == PredictedStarClassification.VISIBLE_IN_VIEWPORT }
        .map { projection ->
            val displayPoint = requireNotNull(projection.displayPoint) {
                "VISIBLE_IN_VIEWPORT projection must carry a displayPoint"
            }
            PredictedStarOverlayPoint(
                catalogIndex = projection.catalogIndex,
                magnitude = projection.magnitude,
                displayX = displayPoint.x,
                displayY = displayPoint.y,
            )
        }

private fun describeIntrinsicsReference(reference: CameraIntrinsicsReference): String =
    when (reference) {
        is CameraIntrinsicsReference.AnalysisBuffer -> "AnalysisBuffer(${reference.widthPx}x${reference.heightPx})"
        CameraIntrinsicsReference.PhysicalSensor -> "PhysicalSensor"
        CameraIntrinsicsReference.Unspecified -> "Unspecified"
    }
