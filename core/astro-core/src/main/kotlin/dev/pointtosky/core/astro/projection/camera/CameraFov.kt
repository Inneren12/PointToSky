package dev.pointtosky.core.astro.projection.camera

import kotlin.math.atan

/**
 * Pure pinhole field-of-view math: `fov = 2 * atan(sensorDimensionMm / (2 * focalLengthMm))`.
 *
 * No Android dependency. Consumed by the `:mobile` Camera2-backed [CameraIntrinsics] resolver
 * (CAM-1b) to turn `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` + `SENSOR_INFO_PHYSICAL_SIZE` into real
 * per-device FOV, replacing the legacy fixed [dev.pointtosky.core.astro.projection.VERTICAL_FOV_DEG]
 * default only inside the new, not-yet-wired intrinsics contract.
 */

/**
 * Derives a field of view in degrees from a physical sensor dimension and focal length, both in
 * millimetres: `fov = 2 * atan(sensorDimensionMm / (2 * focalLengthMm))`.
 *
 * For any finite, positive inputs the result is strictly within `0 < fov < 180` degrees, since
 * `atan` is strictly bounded within `(-pi/2, pi/2)` for all real arguments.
 *
 * @throws IllegalArgumentException if either input is non-finite (NaN/Infinity) or not positive.
 *   Invalid device metadata must be rejected here, not silently clamped or guessed.
 */
fun fovDegFromFocalLength(sensorDimensionMm: Double, focalLengthMm: Double): Double {
    require(sensorDimensionMm.isFinite()) { "sensorDimensionMm must be finite; was $sensorDimensionMm" }
    require(sensorDimensionMm > 0.0) { "sensorDimensionMm must be positive; was $sensorDimensionMm" }
    require(focalLengthMm.isFinite()) { "focalLengthMm must be finite; was $focalLengthMm" }
    require(focalLengthMm > 0.0) { "focalLengthMm must be positive; was $focalLengthMm" }

    val halfFovRad = atan(sensorDimensionMm / (2.0 * focalLengthMm))
    return Math.toDegrees(2.0 * halfFovRad)
}

/** [fovDegFromFocalLength] specialized for the sensor's physical width (horizontal FOV). */
fun horizontalFovDeg(sensorWidthMm: Double, focalLengthMm: Double): Double =
    fovDegFromFocalLength(sensorWidthMm, focalLengthMm)

/** [fovDegFromFocalLength] specialized for the sensor's physical height (vertical FOV). */
fun verticalFovDeg(sensorHeightMm: Double, focalLengthMm: Double): Double =
    fovDegFromFocalLength(sensorHeightMm, focalLengthMm)
