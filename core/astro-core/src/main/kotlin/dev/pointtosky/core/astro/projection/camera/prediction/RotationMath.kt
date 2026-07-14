package dev.pointtosky.core.astro.projection.camera.prediction

private const val ROTATION_MATRIX_SIZE = 9

/**
 * Applies [dev.pointtosky.core.astro.projection.camera.TimedRotationSample.rotationMatrix] (or any
 * row-major 3×3 rotation matrix in that same `FloatArray(9)` layout) to a world-space
 * [LocalSkyDirection], producing the corresponding [DeviceVector].
 *
 * ## Which direction does `rotationMatrix` map?
 * `rotationMatrix` maps **device → world**: `v_world = R · v_device`. This is traced, not guessed,
 * from the production sensor pipeline:
 *  - `RotationFrame.forwardWorld` (`mobile/.../ar/RotationFrame.kt`) is computed as `(-R[2], -R[5],
 *    -R[8])` — the negated **third column** of `R` — and is documented as "the world direction the
 *    back camera points at". Multiplying `R` (row-major, `R[i*3+j]`) by the device unit vector
 *    `(0, 0, -1)` (the device `-Z` axis the back camera looks along, per
 *    [DeviceToOpticalCameraTransform]'s own KDoc) picks out exactly that negated third column:
 *    `R · (0,0,-1) = (-R[2], -R[5], -R[8])`. So `R` applied to a *device*-frame vector yields a
 *    *world*-frame vector — i.e. `R` is device→world.
 *  - `ArScreen.calculateOverlay` independently derives `worldToDevice = transpose(rotationMatrix)`
 *    from this exact same `R` for its own (legacy fixed-FOV) projection — confirming the transpose,
 *    not `R` itself, is the world→device direction.
 *
 * Since `R` is a rotation matrix (orthonormal), its transpose is its inverse, so prediction — which
 * needs to know where a *world*-space star direction falls in *device* space — uses:
 * ```text
 * v_device = Rᵀ · v_world
 * ```
 * implemented below as a direct transpose-multiply (`device_i = Σⱼ R[j·3+i] · world_j`, i.e. row `i`
 * of `Rᵀ` is column `i` of `R`) — not by calling
 * [dev.pointtosky.core.astro.projection.transpose]/[dev.pointtosky.core.astro.projection.multiply],
 * which operate on `FloatArray`/`Float`. Converting each matrix element to `Double` once up front and
 * multiplying in `Double` means literal test matrices (identity, exact 90°/180° rotations, whose
 * entries are exactly representable as `Float`) round-trip through this function with **zero**
 * additional floating-point error — needed for the hand-computed pinhole test expectations
 * downstream.
 */
fun worldToDeviceVector(
    rotationMatrix: FloatArray,
    world: LocalSkyDirection,
): DeviceVector {
    require(rotationMatrix.size == ROTATION_MATRIX_SIZE) {
        "rotationMatrix must have exactly $ROTATION_MATRIX_SIZE elements; was ${rotationMatrix.size}"
    }
    val r = DoubleArray(ROTATION_MATRIX_SIZE) { rotationMatrix[it].toDouble() }
    return DeviceVector(
        x = r[0] * world.x + r[3] * world.y + r[6] * world.z,
        y = r[1] * world.x + r[4] * world.y + r[7] * world.z,
        z = r[2] * world.x + r[5] * world.y + r[8] * world.z,
    )
}
