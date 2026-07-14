package dev.pointtosky.mobile.ar.camera.prediction

import dev.pointtosky.core.astro.projection.camera.prediction.IntrinsicsMappingUnavailableReason
import dev.pointtosky.core.astro.projection.camera.prediction.StarPredictionSummary
import dev.pointtosky.mobile.ar.camera.CameraGeometryDiagnosticCategory

/** [dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata.rotationDegrees]'s own valid set. */
private val VALID_ROTATIONS_DEG = setOf(0, 90, 180, 270)

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
) {
    init {
        require(catalogIndex >= 0) { "catalogIndex must be non-negative; was $catalogIndex" }
        require(magnitude == null || magnitude.isFinite()) { "magnitude must be finite when present; was $magnitude" }
        require(displayX.isFinite()) { "displayX must be finite; was $displayX" }
        require(displayY.isFinite()) { "displayY must be finite; was $displayY" }
    }
}

/**
 * CAM-2b: bounded, immutable diagnostic metadata for the compact status panel (§9 of the task). Never
 * retains a rotation matrix, a catalog object, image pixels, an exception stack, a historical frame, or
 * a device identifier — only the plain scalars/strings a debug panel needs to render one line each.
 *
 * [sessionIntrinsicsSource]/[sessionIntrinsicsReference] always describe the camera session's own
 * resolved-or-fallback intrinsics ([dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry.intrinsics]),
 * regardless of [intrinsicsMode]. [projectionIntrinsicsSource]/[projectionIntrinsicsReference] describe
 * whichever intrinsics were *actually used* to call `projectStars` — identical to the session ones for
 * [PredictedStarOverlayIntrinsicsMode.SESSION_INTRINSICS], but the substituted diagnostic fallback for
 * [PredictedStarOverlayIntrinsicsMode.DIAGNOSTIC_ANALYSIS_BUFFER_FALLBACK]. Keeping both pairs distinct
 * means the panel can never describe a diagnostic fallback substitution as if it were the real,
 * calibrated session intrinsics.
 *
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
    val intrinsicsMode: PredictedStarOverlayIntrinsicsMode,
    val sessionIntrinsicsSource: String,
    val sessionIntrinsicsReference: String,
    val projectionIntrinsicsSource: String,
    val projectionIntrinsicsReference: String,
    val magneticDeclinationDeg: Double,
) {
    init {
        require(inputCount >= 0) { "inputCount must be non-negative; was $inputCount" }
        require(visibleCount >= 0) { "visibleCount must be non-negative; was $visibleCount" }
        require(frameRotationDegrees in VALID_ROTATIONS_DEG) {
            "frameRotationDegrees must be one of $VALID_ROTATIONS_DEG; was $frameRotationDegrees"
        }
        require(magneticDeclinationDeg.isFinite()) {
            "magneticDeclinationDeg must be finite; was $magneticDeclinationDeg"
        }
    }
}

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
     *
     * The primary constructor is `private` (`@ConsistentCopyVisibility`, matching this codebase's
     * `StarPredictionBatchResult.Ready`/`EquatorialStarDirection` convention): the sole public
     * construction path is [of], which stores a **defensive copy** ([List.toList]) of [points] — this
     * is a diagnostic snapshot and must never be externally mutable, either by a caller holding the
     * original (possibly mutable) list, or via a reference to an already-published [Ready]'s own
     * [points]. `init` also re-checks the cross-field invariants [of] establishes, as defense in depth.
     */
    @ConsistentCopyVisibility
    data class Ready private constructor(
        val points: List<PredictedStarOverlayPoint>,
        val summary: StarPredictionSummary,
        val metadata: PredictedStarOverlayMetadata,
    ) : PredictedStarOverlayState {
        init {
            require(points.size == metadata.visibleCount) {
                "points.size (${points.size}) must equal metadata.visibleCount (${metadata.visibleCount})"
            }
            require(summary.inputCount == metadata.inputCount) {
                "summary.inputCount (${summary.inputCount}) must equal metadata.inputCount (${metadata.inputCount})"
            }
            require(summary.visibleInViewportCount == metadata.visibleCount) {
                "summary.visibleInViewportCount (${summary.visibleInViewportCount}) must equal " +
                    "metadata.visibleCount (${metadata.visibleCount})"
            }
        }

        companion object {
            /** The only public way to build a [Ready]; see the class KDoc for why. */
            fun of(
                points: List<PredictedStarOverlayPoint>,
                summary: StarPredictionSummary,
                metadata: PredictedStarOverlayMetadata,
            ): Ready = Ready(points.toList(), summary, metadata)
        }
    }

    /** `projectStars(...)` returned `IntrinsicsMappingUnavailable`; see [reason]. */
    data class Unavailable(val reason: IntrinsicsMappingUnavailableReason) : PredictedStarOverlayState
}
