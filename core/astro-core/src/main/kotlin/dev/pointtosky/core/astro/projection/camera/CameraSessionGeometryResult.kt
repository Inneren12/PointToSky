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

/** Why [CameraSessionGeometryResult.GeometryRejected] was returned (CAM-1f). */
enum class GeometryRejectionReason {
    /**
     * The [FrameRotationPairingResult.Paired] result's frame timestamp did not match the frame
     * supplied to [createCameraSessionGeometry] — the pairing was computed for a different frame.
     */
    FRAME_ROTATION_TIMESTAMP_MISMATCH,

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
 * [viewportWidthPx]/[viewportHeightPx] are plain, possibly-invalid integers rather than a
 * pre-built [PixelSize]: [PixelSize] cannot represent a zero/negative size, so a zero viewport
 * must be checked *before* any [PixelSize] is constructed, and reported as
 * [CameraSessionGeometryResult.InvalidViewport] — never thrown, never silently substituted with a
 * fake `1x1` size.
 *
 * Every rejection path returns a categorized [CameraSessionGeometryResult] rather than throwing —
 * these are expected runtime outcomes (stale pairing, out-of-tolerance motion, a not-yet-ready
 * viewport), not programmer-contract violations — and no result carries raw exception text.
 *
 * @throws IllegalArgumentException only for a genuine programmer-contract violation surfaced by
 *   [CameraSessionGeometry]'s own invariant checks, which should be unreachable given the
 *   validation this function performs first.
 */
fun createCameraSessionGeometry(
    frame: CameraFrameMetadata,
    pairingResult: FrameRotationPairingResult,
    intrinsicsResolution: CameraIntrinsicsResolution,
    viewportWidthPx: Int,
    viewportHeightPx: Int,
): CameraSessionGeometryResult {
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

    if (paired.pair.frame.timestampNanos != frame.timestampNanos) {
        return CameraSessionGeometryResult.GeometryRejected(GeometryRejectionReason.FRAME_ROTATION_TIMESTAMP_MISMATCH)
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
