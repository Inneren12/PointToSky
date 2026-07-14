package dev.pointtosky.mobile.ar.camera.prediction

import dev.pointtosky.core.astro.projection.camera.prediction.IntrinsicsMappingUnavailableReason
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionSummary
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticCategory

/**
 * CAM-2b: why [PredictedStarOverlayState.Waiting] was reported instead of [PredictedStarOverlayState.Ready].
 * Never derived from a `toString()` — one value per distinct reason the CAM-2b integration can be
 * blocked, so a physical-device session can tell *which* prerequisite is missing (§6 of the task).
 */
sealed interface PredictedStarOverlayWaitingReason {
    /**
     * The CAM-1c-1f/1g camera-geometry pipeline is not [dev.pointtosky.core.astro.projection.camera.CameraSessionGeometryResult.Ready]
     * yet. [category] reuses the existing CAM-1g classification ([CameraGeometryDiagnosticCategory])
     * rather than re-deriving a second, parallel one — see that type's own `READY_*` values are never
     * produced here (a `Ready` geometry result never reaches this branch).
     */
    data class GeometryNotReady(val category: CameraGeometryDiagnosticCategory) : PredictedStarOverlayWaitingReason

    /** The device location has not resolved yet (mirrors `ArUiState.Ready.locationResolved == false`). */
    data object ObserverLocationUnavailable : PredictedStarOverlayWaitingReason

    /** No coherent observation instant is available yet (the sky-render state has not produced one). */
    data object ObservationTimeUnavailable : PredictedStarOverlayWaitingReason

    /**
     * The legacy renderer's own magnetic declination value ([android.hardware.GeomagneticField]) was
     * not finite. Never silently treated as `0.0` — see `StarProjectionContext`'s own KDoc for why a
     * silent `0.0` fallback would misrepresent an uncorrected prediction as true-north-corrected.
     */
    data object MagneticDeclinationUnavailable : PredictedStarOverlayWaitingReason

    /** The bounded diagnostic catalog subset ([selectPredictedStarDirections]) is empty. */
    data object NoStarsSelected : PredictedStarOverlayWaitingReason
}

/**
 * CAM-2b: one predicted star, already classified [dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarClassification.VISIBLE_IN_VIEWPORT]
 * and reduced to exactly what the overlay draws. [displayX]/[displayY] are copied verbatim from
 * [dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarProjection.displayPoint] — no
 * additional scale, rotation, crop offset, or rounding is ever applied (§8 of the task); CAM-2a already
 * returns final display coordinates.
 */
data class PredictedStarOverlayPoint(
    val catalogIndex: Int,
    val magnitude: Double?,
    val displayX: Double,
    val displayY: Double,
)

/**
 * CAM-2b: bounded, immutable diagnostic metadata for the compact status panel (§9 of the task). Never
 * retains a rotation matrix, a catalog object, image pixels, an exception stack, a historical frame, or
 * a device identifier — only the plain scalars/strings a debug panel needs to render one line each.
 *
 * @property intrinsicsSource [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource.name]
 *   of the geometry bundle's resolved intrinsics.
 * @property intrinsicsReference a short, human-readable description of
 *   [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference] — e.g. `"AnalysisBuffer(1920x1080)"`.
 * @property magneticDeclinationDeg the declination actually fed into [dev.pointtosky.core.astro.projection.camera.prediction.StarProjectionContext],
 *   in degrees. Never `null` for a [PredictedStarOverlayState.Ready] result — a `null`/non-finite
 *   declination is a [PredictedStarOverlayWaitingReason.MagneticDeclinationUnavailable] `Waiting` state,
 *   not a `Ready` result with a fabricated `0.0`.
 */
data class PredictedStarOverlayMetadata(
    val inputCount: Int,
    val visibleCount: Int,
    val frameTimestampNanos: Long,
    val rotationTimestampNanos: Long,
    val pairDeltaNanos: Long,
    val frameRotationDegrees: Int,
    val intrinsicsSource: String,
    val intrinsicsReference: String,
    val magneticDeclinationDeg: Double,
)

/**
 * CAM-2b: the small, immutable, UI-facing diagnostic state for the predicted-star overlay (task §5).
 * Built exactly once per coherent recomputation by [reducePredictedStarOverlayState] — never mutated,
 * never grown, never carrying a rotation matrix, catalog object, raw pixel data, exception stack, or
 * device identifier.
 */
sealed interface PredictedStarOverlayState {
    /** The `internalDebug`-only gate ([dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticsGate]) is off. */
    data object Disabled : PredictedStarOverlayState

    /** A required prerequisite (§6 of the task) is not yet coherent; see [reason]. */
    data class Waiting(val reason: PredictedStarOverlayWaitingReason) : PredictedStarOverlayState

    /**
     * All prerequisites were coherent and `projectStars(...)` ran. [points] carries only
     * [dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarClassification.VISIBLE_IN_VIEWPORT]
     * predictions, in CAM-2a's own input/result order; [summary] is the full-batch counters (including
     * non-visible classifications), never filtered.
     */
    data class Ready(
        val points: List<PredictedStarOverlayPoint>,
        val summary: StarPredictionSummary,
        val metadata: PredictedStarOverlayMetadata,
    ) : PredictedStarOverlayState

    /** `projectStars(...)` returned `IntrinsicsMappingUnavailable`; see [reason]. */
    data class Unavailable(val reason: IntrinsicsMappingUnavailableReason) : PredictedStarOverlayState
}
