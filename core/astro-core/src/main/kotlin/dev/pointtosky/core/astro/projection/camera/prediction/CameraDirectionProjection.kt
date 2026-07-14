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
 * The result of projecting one [LocalSkyDirection] into optical-camera space (CAM-2a §6): either a
 * direction strictly in front of the camera, with pinhole-normalized coordinates, or an explicit
 * behind-camera rejection. There is no third, silently-wrong outcome — a non-finite intermediate value
 * is folded into [BehindCamera] rather than ever being returned as a normalized coordinate.
 */
sealed interface CameraDirectionProjection {
    /**
     * @property cameraX optical-camera X (see [OpticalCameraVector]).
     * @property cameraY optical-camera Y.
     * @property cameraZ optical-camera Z (forward depth); always `> `[FORWARD_EPSILON].
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
 * Converts a world-space [LocalSkyDirection] into a [CameraDirectionProjection]: applies the
 * device←world rotation ([worldToDeviceVector]) then the fixed device→optical basis change
 * ([DeviceToOpticalCameraTransform]), and divides by depth only when strictly in front of the camera.
 *
 * [rotationMatrix] must be exactly
 * [dev.pointtosky.core.astro.projection.camera.TimedRotationSample.rotationMatrix] (or an
 * equally-shaped row-major `FloatArray(9)`) for the frame this direction is being projected against —
 * this function does not read a [dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry]
 * itself, so it cannot enforce that the matrix and direction were paired for the same call; the
 * batch API (`projectStars`) is responsible for that pairing.
 */
fun projectToCameraDirection(
    world: LocalSkyDirection,
    rotationMatrix: FloatArray,
): CameraDirectionProjection {
    val device = worldToDeviceVector(rotationMatrix, world)
    val optical = DeviceToOpticalCameraTransform.apply(device)
    if (optical.z <= FORWARD_EPSILON) return CameraDirectionProjection.BehindCamera

    val normalizedX = optical.x / optical.z
    val normalizedY = optical.y / optical.z
    if (!normalizedX.isFinite() || !normalizedY.isFinite()) return CameraDirectionProjection.BehindCamera

    return CameraDirectionProjection.InFront(
        cameraX = optical.x,
        cameraY = optical.y,
        cameraZ = optical.z,
        normalizedX = normalizedX,
        normalizedY = normalizedY,
    )
}
