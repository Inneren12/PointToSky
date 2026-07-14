package dev.pointtosky.core.astro.projection.camera.prediction

/**
 * A device-space vector expressed in the display-aligned sensor/attitude basis — the same basis
 * [dev.pointtosky.core.astro.projection.projectDeviceVector] documents for its own (legacy, 56°-FOV)
 * projection and that [worldToDeviceVector] produces: `+x = display right`, `+y = display up`, `+z =
 * out of the display toward the user`; the back camera looks along **-z**.
 */
data class DeviceVector(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "DeviceVector components must be finite; was ($x, $y, $z)"
        }
    }
}

/**
 * A vector expressed in the optical pinhole-camera basis this package projects in: `+x = image
 * right`, `+y = image down`, `+z = forward` (into the scene, away from the lens — **positive** for
 * anything in front of the camera).
 *
 * `+y = down` (not `up`) is a deliberate choice, not a coin flip: [PixelPoint][dev.pointtosky.core.astro.projection.camera.PixelPoint]
 * and [CropScaleTransform][dev.pointtosky.core.astro.projection.camera.CropScaleTransform] both
 * document image/buffer axes as `+x` right, `+y` down. Making the optical camera frame agree means
 * [PinholeProjectionModel.project] needs no extra Y-flip (`u = fx·normalizedX + cx`, `v = fy·normalizedY
 * + cy`, both with a **positive** focal length) to land in that same buffer-pixel convention.
 */
data class OpticalCameraVector(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "OpticalCameraVector components must be finite; was ($x, $y, $z)"
        }
    }
}

/**
 * The one fixed device(sensor/attitude)→optical-camera basis change CAM-2a uses, so no sign flip is
 * scattered across the rest of the projection code (per the CAM-2a spec's explicit requirement).
 *
 * Device frame → optical frame:
 *  - `opticalX = deviceX` (right stays right).
 *  - `opticalY = -deviceY` (device "up" is optical "smaller row", i.e. negative image-down).
 *  - `opticalZ = -deviceZ` (the camera looks along device `-Z`, which becomes optical `+Z`, i.e.
 *    positive depth in front of the lens).
 *
 * This exact sign pattern is not a fresh guess: it is the pattern implied by
 * [dev.pointtosky.core.astro.projection.projectDeviceVector]'s own already-tested legacy formulas.
 * There, `ndcX = deviceX / (-deviceZ)` and screen `Y` grows with `deviceY` (`screenY = halfHeight ·
 * (1 - ndcY)`, and positive `deviceY` maps to a *smaller* screen `Y` — see
 * `ProjectionTest."positive device Y projects upward in NDC but to a lower screen Y"`). Substituting
 * `opticalZ = -deviceZ` and `opticalY = -deviceY` here reproduces both signs exactly:
 * `normalizedX = opticalX / opticalZ = deviceX / (-deviceZ)` matches `ndcX`, and `normalizedY =
 * opticalY / opticalZ = -deviceY / (-deviceZ) = deviceY / opticalZ`, whose sign agrees with legacy
 * `screenY` growing with `normalizedY` once [PinholeProjectionModel] uses a **positive** `fy` — see
 * `docs/camera_coordinate_calibration_contract.md` CAM-2a §… for the full derivation.
 */
object DeviceToOpticalCameraTransform {
    fun apply(device: DeviceVector): OpticalCameraVector =
        OpticalCameraVector(x = device.x, y = -device.y, z = -device.z)
}
