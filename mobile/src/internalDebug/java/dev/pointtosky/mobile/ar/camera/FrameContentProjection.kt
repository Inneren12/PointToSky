package dev.pointtosky.mobile.ar.camera

/**
 * CAM-2c frame-content correspondence experiment (`internalDebug`-only, task §5). Typed reasons a
 * predicted-point projection is excluded from residual statistics — **never** a silent drop; every
 * rejected point is still counted and reported by reason (see `FrameContentResidual.kt`).
 */
internal enum class FrameContentPointRejectionReason {
    /** This hypothesis's own camera matrix could not be resolved at all (task §3's `NOT_IMPLEMENTED`
     * reconciled path, or an intrinsics resolution failure) — no projection was attempted. */
    HYPOTHESIS_UNAVAILABLE,

    /** The object point's camera-frame Z is at or behind the camera plane (`<= 0`, within a small
     * numerical-safety margin) — a homogeneous-division-by-non-positive-Z case. */
    BEHIND_CAMERA,

    /** The point's active-array-local projection (before the sensor-to-buffer matrix is applied) falls
     * outside `[0, activeArrayWidthPx] x [0, activeArrayHeightPx]` — the pinhole model is being asked
     * to describe a ray this physical sensor's own active array could not have captured. */
    OUTSIDE_SOURCE_DOMAIN,

    /** The projected pixel coordinate is non-finite (`NaN`/`Infinity`) — a genuinely invalid
     * homogeneous division, distinct from [BEHIND_CAMERA] (which is caught earlier, before division). */
    INVALID_HOMOGENEOUS_DIVISION,

    /** The projected buffer pixel coordinate is finite but falls outside
     * `[0, bufferWidthPx] x [0, bufferHeightPx]`. */
    OUTSIDE_BUFFER_BOUNDS,
}

internal sealed interface FrameContentProjectionOutcome {
    data class Projected(
        val xPx: Double,
        val yPx: Double,
        val cameraZMm: Double,
    ) : FrameContentProjectionOutcome

    data class Rejected(
        val reason: FrameContentPointRejectionReason,
    ) : FrameContentProjectionOutcome
}

/** Camera-frame Z (millimetres) at or below this is treated as "behind or on the camera plane" — a
 * small positive floor rather than exactly `0.0`, so a numerically-noisy near-zero Z is still rejected
 * as [FrameContentPointRejectionReason.BEHIND_CAMERA] rather than producing a huge, meaningless
 * projected pixel value from dividing by a near-zero denominator. */
internal const val FRAME_CONTENT_MIN_CAMERA_Z_MM: Double = 1e-3

/**
 * Projects [objectPoint] through [pose] and one hypothesis's [intrinsics] (task §3/§5): rotate+translate
 * into the camera frame, reject [FrameContentPointRejectionReason.BEHIND_CAMERA] on non-positive Z,
 * pinhole-project into the physical camera's own active-array-local basis and reject
 * [FrameContentPointRejectionReason.OUTSIDE_SOURCE_DOMAIN] outside that array, pinhole-project into the
 * hypothesis's own buffer-space basis and reject [FrameContentPointRejectionReason.INVALID_HOMOGENEOUS_DIVISION]
 * (non-finite) or [FrameContentPointRejectionReason.OUTSIDE_BUFFER_BOUNDS] (finite but off-buffer).
 * Distortion is never applied — see [FRAME_CONTENT_DISTORTION_BASIS].
 */
internal fun projectObjectPoint(
    objectPoint: FrameContentObjectPoint,
    pose: FrameContentPoseSolution,
    intrinsics: FrameContentHypothesisIntrinsics,
): FrameContentProjectionOutcome {
    val objectVec = Vec3(objectPoint.xMm, objectPoint.yMm, objectPoint.zMm)
    val cameraPoint = pose.rotation.apply(objectVec) + pose.translationMm

    if (cameraPoint.z <= FRAME_CONTENT_MIN_CAMERA_Z_MM) {
        return FrameContentProjectionOutcome.Rejected(FrameContentPointRejectionReason.BEHIND_CAMERA)
    }

    val normalizedX = cameraPoint.x / cameraPoint.z
    val normalizedY = cameraPoint.y / cameraPoint.z

    val activeX = intrinsics.activeFxPx * normalizedX + intrinsics.activeCxPx
    val activeY = intrinsics.activeFyPx * normalizedY + intrinsics.activeCyPx
    if (!activeX.isFinite() || !activeY.isFinite()) {
        return FrameContentProjectionOutcome.Rejected(FrameContentPointRejectionReason.INVALID_HOMOGENEOUS_DIVISION)
    }
    if (activeX < 0.0 || activeX > intrinsics.activeArrayWidthPx || activeY < 0.0 || activeY > intrinsics.activeArrayHeightPx) {
        return FrameContentProjectionOutcome.Rejected(FrameContentPointRejectionReason.OUTSIDE_SOURCE_DOMAIN)
    }

    val bufferX = intrinsics.bufferFxPx * normalizedX + intrinsics.bufferCxPx
    val bufferY = intrinsics.bufferFyPx * normalizedY + intrinsics.bufferCyPx
    if (!bufferX.isFinite() || !bufferY.isFinite()) {
        return FrameContentProjectionOutcome.Rejected(FrameContentPointRejectionReason.INVALID_HOMOGENEOUS_DIVISION)
    }
    if (bufferX < 0.0 || bufferX > intrinsics.bufferWidthPx || bufferY < 0.0 || bufferY > intrinsics.bufferHeightPx) {
        return FrameContentProjectionOutcome.Rejected(FrameContentPointRejectionReason.OUTSIDE_BUFFER_BOUNDS)
    }

    return FrameContentProjectionOutcome.Projected(bufferX, bufferY, cameraPoint.z)
}
