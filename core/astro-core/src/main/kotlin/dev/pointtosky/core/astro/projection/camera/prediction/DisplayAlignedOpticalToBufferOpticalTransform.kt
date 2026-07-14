package dev.pointtosky.core.astro.projection.camera.prediction

/**
 * A vector expressed in the **native, unrotated analysis-buffer** optical frame — the frame
 * [PinholeProjectionModel] actually projects in (CAM-2a §7), *not* the display-aligned optical frame
 * [OpticalCameraVector] represents. Same axis convention as [OpticalCameraVector] (`+x` right, `+y`
 * down, `+z` forward), just re-expressed relative to the buffer's own, un-rotated row/column axes
 * instead of the display's. A distinct type on purpose: mixing this up with [OpticalCameraVector] is
 * exactly the CAM-2a bug this file exists to close (see [DisplayAlignedOpticalToBufferOpticalTransform]).
 */
data class BufferOpticalCameraVector(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    init {
        require(x.isFinite() && y.isFinite() && z.isFinite()) {
            "BufferOpticalCameraVector components must be finite; was ($x, $y, $z)"
        }
    }
}

/**
 * Converts a **display-aligned** optical ray ([OpticalCameraVector], produced by
 * [DeviceToOpticalCameraTransform] from [geometry].pairedRotation, which — after `remapForDisplay` —
 * is expressed relative to the *current display orientation*) into the **native, unrotated
 * analysis-buffer** optical frame [PinholeProjectionModel] actually projects in.
 *
 * ## Why this is needed
 * [worldToDeviceVector]/[DeviceToOpticalCameraTransform] operate on
 * [dev.pointtosky.core.astro.projection.camera.TimedRotationSample.rotationMatrix], which is the
 * production sensor attitude **after** `RotationFrame`'s `remapForDisplay` call
 * (`docs/camera_coordinate_calibration_contract.md` §1.3/§1.4) — i.e. `+x`/`+y` are display-right and
 * display-up for whatever the display's current rotation happens to be, not the sensor's own fixed
 * row/column axes. [PinholeProjectionModel], on the other hand, projects into the **native, unrotated,
 * uncropped analysis buffer** (`frame.bufferWidthPx × frame.bufferHeightPx`) — the exact input
 * [dev.pointtosky.core.astro.projection.camera.CropScaleTransform.imageToDisplay] expects. Feeding a
 * *display-aligned* optical ray straight into the buffer-space pinhole model silently mixes the two
 * bases: for `rotationDegrees ∈ {90, 270}` the display orientation is already baked into the attitude
 * axes, and `CropScaleTransform.imageToDisplay` would then rotate the (already display-oriented) point
 * a second time. This transform is the one explicit, pure correction for that mismatch, applied
 * exactly once, so no other code in this package needs its own rotation logic.
 *
 * ## Derivation (not guessed): the exact inverse of `CropScaleTransform`'s own pixel rotation
 * [dev.pointtosky.core.astro.projection.camera.CropScaleTransform]'s private `rotateClockwise(x, y,
 * w, h, rotationDegrees)` maps a crop-local buffer point `(x, y)` (unrotated crop size `w × h`) to its
 * rotated-image-local point `(xr, yr)`:
 * ```text
 *   0°: xr = x,      yr = y
 *  90°: xr = h − y,  yr = x
 * 180°: xr = w − x,  yr = h − y
 * 270°: xr = y,      yr = w − x
 * ```
 * Only the *linear* part of this (the Jacobian — a ray has no position, so the `w`/`h` translation
 * terms drop out) matters for a direction vector:
 * ```text
 *   0°: J = [[ 1, 0], [ 0, 1]]      90°: J = [[ 0,-1], [ 1, 0]]
 * 180°: J = [[-1, 0], [ 0,-1]]     270°: J = [[ 0, 1], [-1, 0]]
 * ```
 * `displayOptical = J(rotationDegrees) · bufferOptical` (buffer→display is what `imageToDisplay`
 * does), so this transform applies **`J(rotationDegrees)⁻¹`** to recover `bufferOptical` from
 * `displayOptical`. Each `J(k·90°)` here is an orthogonal (rotation-like) matrix, so its inverse is
 * its transpose; concretely `J(90°)⁻¹ = J(270°)` and `J(270°)⁻¹ = J(90°)` (verified: `J(90°)·J(270°) =
 * I`), while `J(0°)` and `J(180°)` are each their own inverse. Substituting gives the exact per-case
 * formula this object implements — see the mapping table below.
 *
 * ## Mapping table (`(dx, dy)` = `displayOptical.x/.y`; `z` is untouched — see below)
 * ```text
 * rotationDegrees   bufferX        bufferY
 *         0            dx             dy
 *        90            dy            −dx
 *       180           −dx            −dy
 *       270           −dy             dx
 * ```
 * Pinned by literal-value tests in `DisplayAlignedOpticalToBufferOpticalTransformTest`, including that
 * composing the 90° and 270° cases (in either order) returns the original vector, and that 180° is its
 * own inverse.
 *
 * ## Depth (`z`) is never touched
 * This is a 2D change of basis around the optical `Z` axis only — `bufferOptical.z ==
 * displayOptical.z` always. Rotating a display's content about its own normal cannot change how far in
 * front of the lens a direction is.
 *
 * @throws IllegalArgumentException if [rotationDegrees] is not one of `0`, `90`, `180`, `270` —
 *   mirrors [dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata]'s own validation of the
 *   same field.
 */
object DisplayAlignedOpticalToBufferOpticalTransform {
    private val VALID_ROTATIONS_DEG = setOf(0, 90, 180, 270)

    fun apply(
        displayOptical: OpticalCameraVector,
        rotationDegrees: Int,
    ): BufferOpticalCameraVector {
        require(rotationDegrees in VALID_ROTATIONS_DEG) {
            "rotationDegrees must be one of $VALID_ROTATIONS_DEG; was $rotationDegrees"
        }
        val dx = displayOptical.x
        val dy = displayOptical.y
        val (bx, by) =
            when (rotationDegrees) {
                0 -> dx to dy
                90 -> dy to -dx
                180 -> -dx to -dy
                else -> -dy to dx // 270
            }
        return BufferOpticalCameraVector(x = bx, y = by, z = displayOptical.z)
    }
}
