package dev.pointtosky.core.astro.projection.camera.prediction

/**
 * Below this optical-camera depth ([OpticalCameraVector.z]), a direction is treated as behind (or
 * tangent to) the camera's image plane and is never divided into normalized coordinates. Mirrors the
 * spirit of [dev.pointtosky.core.astro.projection.projectDeviceVector]'s own `z >= -0.01f` guard
 * (there in device-frame `-z`; here in optical-frame `+z`, so the comparison direction flips) without
 * reusing its specific (single-precision, UI-tuned) threshold — CAM-2a's forward gate only needs to
 * exclude non-positive/degenerate depth, not tune a rendering cutoff.
 */
internal const val FORWARD_EPSILON: Double = 1e-9

/**
 * The result of projecting one [LocalSkyDirection] into **native-buffer** optical-camera space
 * (CAM-2a §6): either a direction strictly in front of the camera, with pinhole-normalized
 * coordinates ready for [PinholeProjectionModel], or an explicit behind-camera rejection. There is no
 * third, silently-wrong outcome — a non-finite intermediate value is folded into [BehindCamera] rather
 * than ever being returned as a normalized coordinate.
 */
sealed interface CameraDirectionProjection {
    /**
     * @property cameraX native-buffer optical-camera X (see [BufferOpticalCameraVector]).
     * @property cameraY native-buffer optical-camera Y.
     * @property cameraZ native-buffer optical-camera Z (forward depth); always `> `[FORWARD_EPSILON].
     * @property normalizedX `cameraX / cameraZ`.
     * @property normalizedY `cameraY / cameraZ`.
     */
    data class InFront(
        val cameraX: Double,
        val cameraY: Double,
        val cameraZ: Double,
        val normalizedX: Double,
        val normalizedY: Double,
    ) : CameraDirectionProjection

    /** [LocalSkyDirection] is behind, or tangent to, the camera's image plane. */
    data object BehindCamera : CameraDirectionProjection
}

/**
 * The forward-gate and pinhole-normalize step, operating on an already-computed
 * [BufferOpticalCameraVector] — i.e. **after** [worldToDeviceVector],
 * [DeviceToOpticalCameraTransform], and [DisplayAlignedOpticalToBufferOpticalTransform] have all been
 * applied (see `CameraStarPredictor.kt` for the full chain). Taking a [BufferOpticalCameraVector]
 * specifically (not a bare `OpticalCameraVector`) makes it a type error to pass a still
 * display-aligned ray here — the exact space-mixing bug this function's split from the old
 * combined `projectToCameraDirection` exists to prevent.
 *
 * Divides by depth only when strictly in front of the camera (`cameraZ > `[FORWARD_EPSILON]`).
 */
fun projectBufferOpticalDirection(bufferOptical: BufferOpticalCameraVector): CameraDirectionProjection {
    if (bufferOptical.z <= FORWARD_EPSILON) return CameraDirectionProjection.BehindCamera

    val normalizedX = bufferOptical.x / bufferOptical.z
    val normalizedY = bufferOptical.y / bufferOptical.z
    if (!normalizedX.isFinite() || !normalizedY.isFinite()) return CameraDirectionProjection.BehindCamera

    return CameraDirectionProjection.InFront(
        cameraX = bufferOptical.x,
        cameraY = bufferOptical.y,
        cameraZ = bufferOptical.z,
        normalizedX = normalizedX,
        normalizedY = normalizedY,
    )
}
