package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.projection.camera.PixelPoint

/**
 * Where one predicted star direction ended up relative to the camera frustum, analyzed source crop,
 * and display viewport (CAM-2a §10). Ordered from "least resolved" to "fully visible"; the ordinal
 * order carries no meaning beyond readability.
 *
 * These four states are deliberately kept distinct — never collapsed into a single boolean — because
 * a point can be in front of the camera but outside the analyzed crop, or inside the crop but removed
 * by `FILL_CENTER`'s center crop; conflating them would hide exactly the geometry CAM-2a exists to
 * make explicit.
 */
enum class PredictedStarClassification {
    /** [CameraDirectionProjection] was [CameraDirectionProjection.BehindCamera]. */
    BEHIND_CAMERA,

    /** In front of the camera, but the projected buffer-space point falls outside `sourceCrop`. */
    OUTSIDE_IMAGE,

    /** Inside `sourceCrop`, but removed by `FILL_CENTER`'s center crop before reaching the viewport. */
    INSIDE_IMAGE_OUTSIDE_VIEWPORT,

    /** Inside `sourceCrop` and inside the visible display viewport. */
    VISIBLE_IN_VIEWPORT,
}

/**
 * Bounded, Android-free snapshot of one in-front camera-direction projection — never a rotation
 * matrix, never a mutable array. See [CameraDirectionProjection.InFront] for the field derivation.
 * All five components are validated finite: a `NaN`/infinite scalar here would otherwise be able to
 * reach a public [PredictedStarProjection] undetected.
 */
data class CameraDirectionSnapshot(
    val cameraX: Double,
    val cameraY: Double,
    val cameraZ: Double,
    val normalizedX: Double,
    val normalizedY: Double,
) {
    init {
        require(cameraX.isFinite() && cameraY.isFinite() && cameraZ.isFinite() && normalizedX.isFinite() && normalizedY.isFinite()) {
            "CameraDirectionSnapshot components must be finite; was " +
                "(cameraX=$cameraX, cameraY=$cameraY, cameraZ=$cameraZ, normalizedX=$normalizedX, normalizedY=$normalizedY)"
        }
    }
}

/**
 * One star's bounded, pure prediction result (CAM-2a §10). Carries only what a future consumer needs
 * to place or reason about the prediction — no catalog object, name, texture, rotation matrix,
 * mutable array, exception text, or device identifier.
 *
 * [cameraDirection]/[imagePoint]/[displayPoint] are all `null` together for
 * [PredictedStarClassification.BEHIND_CAMERA], and all three non-null together for every other
 * classification — since a point can be geometrically computed and still classified as not visible
 * (outside the crop, or inside the crop but outside the viewport); nothing here is ever clamped into
 * looking visible. This is enforced in `init`, not just documented: there is no public construction
 * path that can produce a `BEHIND_CAMERA` result carrying pixel data, or a non-`BEHIND_CAMERA` result
 * missing it.
 *
 * @property catalogIndex mirrors [EquatorialStarDirection.catalogIndex] — the only identity carried
 *   through; no catalog object is retained.
 * @property magnitude mirrors [EquatorialStarDirection.magnitude].
 */
data class PredictedStarProjection(
    val catalogIndex: Int,
    val magnitude: Double?,
    val classification: PredictedStarClassification,
    val cameraDirection: CameraDirectionSnapshot?,
    val imagePoint: PixelPoint?,
    val displayPoint: PixelPoint?,
) {
    init {
        require(catalogIndex >= 0) { "catalogIndex must be non-negative; was $catalogIndex" }
        require(magnitude == null || magnitude.isFinite()) { "magnitude must be finite when present; was $magnitude" }

        val allNull = cameraDirection == null && imagePoint == null && displayPoint == null
        val allNonNull = cameraDirection != null && imagePoint != null && displayPoint != null
        require(allNull || allNonNull) {
            "cameraDirection/imagePoint/displayPoint must be all null or all non-null together; " +
                "was (cameraDirection=$cameraDirection, imagePoint=$imagePoint, displayPoint=$displayPoint)"
        }
        if (classification == PredictedStarClassification.BEHIND_CAMERA) {
            require(allNull) { "BEHIND_CAMERA must carry no camera direction or pixel data" }
        } else {
            require(allNonNull) { "$classification must carry cameraDirection/imagePoint/displayPoint" }
        }
    }
}
