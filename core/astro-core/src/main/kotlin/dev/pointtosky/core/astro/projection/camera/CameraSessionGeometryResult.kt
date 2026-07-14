package dev.pointtosky.core.astro.projection.camera

/**
 * Why [CameraSessionGeometryResult.RotationUnavailable] was returned (CAM-1f) — mirrors
 * [FrameRotationPairingResult]'s non-[FrameRotationPairingResult.Paired] cases one for one.
 */
enum class RotationUnavailableReason {
    NO_SAMPLES,
    OUTSIDE_TOLERANCE,
    CLOCK_MISMATCH_SUSPECTED,
}

/**
 * Why [CameraSessionGeometryResult.IntrinsicsUnavailable] was returned (CAM-1f).
 *
 * Never returned by the pure factory [createCameraSessionGeometry] itself — it always requires an
 * already-resolved [CameraIntrinsicsResolution] as input. This exists for the production session
 * owner (`dev.pointtosky.mobile.ar.camera.CameraSessionGeometryProvider`), which publishes this
 * status while a bound camera session has not yet resolved intrinsics.
 */
enum class IntrinsicsUnavailableReason {
    /** No [CameraIntrinsicsResolution] has been reported to the session owner yet. */
    PENDING,
}

/**
 * Why [CameraSessionGeometryResult.GeometryRejected] was returned (CAM-1f).
 *
 * [FrameRotationPairingResult.Paired] and [FrameRotationPair] are both publicly constructible —
 * neither can be trusted as proof that it actually originated from [pairFrameToNearestRotation].
 * [createCameraSessionGeometry] therefore validates their contents explicitly rather than assuming
 * anything about how a `Paired` value was produced; [PAIRING_FRAME_MISMATCH],
 * [PAIRING_DELTA_MISMATCH], and [PAIRING_OUTSIDE_CONFIGURED_TOLERANCE] are the categorized outcomes
 * of that validation.
 */
enum class GeometryRejectionReason {
    /**
     * `paired.pair.frame != frame` supplied to [createCameraSessionGeometry] — exact metadata
     * equality, not just a timestamp match, so a forged or stale pairing whose frame differs only
     * in dimensions, crop rect, or rotation (but shares the same timestamp) is still caught.
     */
    PAIRING_FRAME_MISMATCH,

    /**
     * `paired.pair.deltaNanos` does not equal the overflow-safe delta between `frame` and
     * `paired.pair.rotation`'s timestamps — the pair's own arithmetic is internally inconsistent.
     */
    PAIRING_DELTA_MISMATCH,

    /**
     * The (verified-correct) delta exceeds the tolerance [createCameraSessionGeometry] was called
     * with, even though the pairing result used the [FrameRotationPairingResult.Paired] subtype —
     * that subtype alone is not proof of tolerance conformance for a publicly constructible type.
     */
    PAIRING_OUTSIDE_CONFIGURED_TOLERANCE,

    /** [CropScaleTransform] construction rejected the supplied frame/viewport geometry. */
    CROP_SCALE_CONSTRUCTION_FAILED,
}

/**
 * Explicit build outcome for [CameraSessionGeometry] (CAM-1f) — never represented as a nullable
 * geometry, so every caller must handle *why* a bundle is not ready rather than treating `null` as
 * one undifferentiated "not available" case.
 */
sealed interface CameraSessionGeometryResult {
    /** A fully-coherent, invariant-checked bundle is ready. [quality] flags fallback intrinsics. */
    data class Ready(
        val geometry: CameraSessionGeometry,
        val quality: CameraGeometryQuality,
    ) : CameraSessionGeometryResult

    /** No camera frame has been observed yet. [viewportSize] is the last known *valid* viewport, if any. */
    data class MissingFrame(
        val viewportSize: PixelSize?,
    ) : CameraSessionGeometryResult

    /** The viewport is zero/negative-sized — never silently mapped to a fake `1x1` transform. */
    data class InvalidViewport(
        val widthPx: Int,
        val heightPx: Int,
    ) : CameraSessionGeometryResult

    /** The supplied [FrameRotationPairingResult] was not a [FrameRotationPairingResult.Paired]. */
    data class RotationUnavailable(
        val reason: RotationUnavailableReason,
        val pairingResult: FrameRotationPairingResult,
    ) : CameraSessionGeometryResult

    /** Camera intrinsics have not been resolved for this bound camera session yet. */
    data class IntrinsicsUnavailable(
        val reason: IntrinsicsUnavailableReason,
    ) : CameraSessionGeometryResult

    /** Every input was present, but a cross-field coherence or construction check failed. */
    data class GeometryRejected(
        val reason: GeometryRejectionReason,
    ) : CameraSessionGeometryResult

    /** The owning session has been disposed; no further geometry will ever be published for it. */
    data object Disposed : CameraSessionGeometryResult
}

/**
 * Cheap, loggable status classification of a [CameraSessionGeometryResult] — carries no reason
 * detail, timestamp, or device information, so it is safe to log/compare on every update.
 */
enum class CameraSessionGeometryStatus {
    READY,
    MISSING_FRAME,
    INVALID_VIEWPORT,
    ROTATION_UNAVAILABLE,
    INTRINSICS_UNAVAILABLE,
    GEOMETRY_REJECTED,
    DISPOSED,
}

/** [CameraSessionGeometryStatus] classification of this result. */
val CameraSessionGeometryResult.status: CameraSessionGeometryStatus
    get() =
        when (this) {
            is CameraSessionGeometryResult.Ready -> CameraSessionGeometryStatus.READY
            is CameraSessionGeometryResult.MissingFrame -> CameraSessionGeometryStatus.MISSING_FRAME
            is CameraSessionGeometryResult.InvalidViewport -> CameraSessionGeometryStatus.INVALID_VIEWPORT
            is CameraSessionGeometryResult.RotationUnavailable -> CameraSessionGeometryStatus.ROTATION_UNAVAILABLE
            is CameraSessionGeometryResult.IntrinsicsUnavailable -> CameraSessionGeometryStatus.INTRINSICS_UNAVAILABLE
            is CameraSessionGeometryResult.GeometryRejected -> CameraSessionGeometryStatus.GEOMETRY_REJECTED
            CameraSessionGeometryResult.Disposed -> CameraSessionGeometryStatus.DISPOSED
        }

/**
 * Pure factory building a [CameraSessionGeometry] from CAM-1c frame metadata, a CAM-1d
 * frame/rotation pairing result, a resolved-or-fallback CAM-1b intrinsics value, and the current
 * display viewport (CAM-1f). No Android dependency, no side effects, retains nothing past the
 * call.
 *
 * Accepts only [FrameRotationPairingResult.Paired] — every other pairing outcome is rejected as
 * [CameraSessionGeometryResult.RotationUnavailable] with a matching [RotationUnavailableReason].
 * This function never rebuilds or reinterprets a rotation sample independently of the supplied
 * pairing result; [CameraSessionGeometry.pairedRotation] is always exactly `pairingResult.pair.rotation`.
 *
 * **A `Paired` value is not trusted as proof of anything by construction** — both
 * [FrameRotationPairingResult.Paired] and [FrameRotationPair] are public data classes any caller
 * can build directly, not just [pairFrameToNearestRotation]. This function therefore validates,
 * before ever building a bundle:
 *  - `paired.pair.frame == frame` (exact metadata equality — [GeometryRejectionReason.PAIRING_FRAME_MISMATCH]);
 *  - `paired.pair.deltaNanos` matches the overflow-safe delta actually implied by the two
 *    timestamps ([GeometryRejectionReason.PAIRING_DELTA_MISMATCH]);
 *  - that (now verified-correct) delta's absolute value does not exceed [maxAllowedPairDeltaNanos]
 *    ([GeometryRejectionReason.PAIRING_OUTSIDE_CONFIGURED_TOLERANCE]) — the `Paired` subtype alone
 *    is never treated as tolerance proof.
 *
 * [maxAllowedPairDeltaNanos] defaults to [TimestampSyncConfig.MAX_PAIR_DELTA_NANOS] but callers
 * whose [FrameRotationPairingResult] was produced with a non-default tolerance (e.g. a
 * `CameraTimestampSynchronizer` constructed with a custom `maxAllowedDeltaNanos`) must pass that
 * same value here — the two must agree, or a correctly-paired result can be wrongly rejected.
 *
 * [viewportWidthPx]/[viewportHeightPx] are plain, possibly-invalid integers rather than a
 * pre-built [PixelSize]: [PixelSize] cannot represent a zero/negative size, so a zero viewport
 * must be checked *before* any [PixelSize] is constructed, and reported as
 * [CameraSessionGeometryResult.InvalidViewport] — never thrown, never silently substituted with a
 * fake `1x1` size.
 *
 * Every rejection path returns a categorized [CameraSessionGeometryResult] rather than throwing —
 * these are expected runtime outcomes (a forged or stale pairing, out-of-tolerance motion, a
 * not-yet-ready viewport), not programmer-contract violations — and no result carries raw
 * exception text.
 *
 * @throws IllegalArgumentException if [maxAllowedPairDeltaNanos] is negative (a programmer-contract
 *   violation at the call site, not an expected runtime outcome), or — expected to be unreachable
 *   given the validation this function performs first — from [CameraSessionGeometry]'s own
 *   invariant checks.
 */
fun createCameraSessionGeometry(
    frame: CameraFrameMetadata,
    pairingResult: FrameRotationPairingResult,
    intrinsicsResolution: CameraIntrinsicsResolution,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
    maxAllowedPairDeltaNanos: Long = TimestampSyncConfig.MAX_PAIR_DELTA_NANOS,
): CameraSessionGeometryResult {
    require(maxAllowedPairDeltaNanos >= 0L) {
        "maxAllowedPairDeltaNanos must be non-negative; was $maxAllowedPairDeltaNanos"
    }

    if (viewportWidthPx <= 0 || viewportHeightPx <= 0) {
        return CameraSessionGeometryResult.InvalidViewport(viewportWidthPx, viewportHeightPx)
    }

    val paired =
        when (pairingResult) {
            is FrameRotationPairingResult.Paired -> pairingResult
            is FrameRotationPairingResult.NoSamples ->
                return CameraSessionGeometryResult.RotationUnavailable(
                    RotationUnavailableReason.NO_SAMPLES,
                    pairingResult,
                )

            is FrameRotationPairingResult.OutsideTolerance ->
                return CameraSessionGeometryResult.RotationUnavailable(
                    RotationUnavailableReason.OUTSIDE_TOLERANCE,
                    pairingResult,
                )

            is FrameRotationPairingResult.ClockMismatchSuspected ->
                return CameraSessionGeometryResult.RotationUnavailable(
                    RotationUnavailableReason.CLOCK_MISMATCH_SUSPECTED,
                    pairingResult,
                )
        }

    if (paired.pair.frame != frame) {
        return CameraSessionGeometryResult.GeometryRejected(GeometryRejectionReason.PAIRING_FRAME_MISMATCH)
    }

    val expectedDelta = overflowSafeDeltaNanos(frame.timestampNanos, paired.pair.rotation.timestampNanos)
    if (paired.pair.deltaNanos != expectedDelta) {
        return CameraSessionGeometryResult.GeometryRejected(GeometryRejectionReason.PAIRING_DELTA_MISMATCH)
    }

    if (overflowSafeAbsNanos(expectedDelta) > maxAllowedPairDeltaNanos) {
        return CameraSessionGeometryResult.GeometryRejected(GeometryRejectionReason.PAIRING_OUTSIDE_CONFIGURED_TOLERANCE)
    }

    val transform =
        try {
            createFillCenterCropScaleTransform(frame, viewportWidthPx, viewportHeightPx)
        } catch (_: IllegalArgumentException) {
            return CameraSessionGeometryResult.GeometryRejected(GeometryRejectionReason.CROP_SCALE_CONSTRUCTION_FAILED)
        }

    val geometry =
        CameraSessionGeometry.of(
            frame = frame,
            pairedRotation = paired.pair.rotation,
            frameRotationDeltaNanos = paired.pair.deltaNanos,
            cropScaleTransform = transform,
            intrinsics = intrinsicsResolution,
            viewportSize = transform.viewportSize,
        )

    return CameraSessionGeometryResult.Ready(geometry = geometry, quality = intrinsicsResolution.quality)
}
