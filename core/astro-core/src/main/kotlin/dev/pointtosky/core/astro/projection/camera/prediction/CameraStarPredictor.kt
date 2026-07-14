package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReferenceSpace
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.CropScaleTransform
import dev.pointtosky.core.astro.projection.camera.referenceSpace

/**
 * Projects [stars] into predicted camera/image/display positions for one [geometry] bundle and
 * observing [context] (CAM-2a). Pure, deterministic, stateless:
 *  - no image pixels are read;
 *  - no observed/detected points exist or are consulted;
 *  - no matching or pose correction occurs;
 *  - no coroutine, prior-call state, or catalog asset access;
 *  - input order is preserved — stars are never reordered or sorted by magnitude.
 *
 * Checks [geometry].intrinsics.intrinsics.referenceSpace **first** (see
 * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReferenceSpace]): a physical-sensor FOV
 * has no recorded mapping to the analyzed buffer's own pixel grid, so this returns
 * [StarPredictionBatchResult.IntrinsicsMappingUnavailable] for the *whole batch* rather than
 * fabricating a buffer-space pinhole model from it. Only once that check passes is
 * [PinholeProjectionModel.forGeometry] read once per call (it is the same for every star in the
 * batch — the rotation, intrinsics, and crop/display transform are all fixed for one [geometry]).
 *
 * For each star: [equatorialToLocalSky] → [worldToDeviceVector] → [DeviceToOpticalCameraTransform] →
 * [DisplayAlignedOpticalToBufferOpticalTransform] → [projectBufferOpticalDirection] → (if in front)
 * [PinholeProjectionModel.project] → crop/viewport classification via `geometry.cropScaleTransform`.
 */
fun projectStars(
    stars: List<EquatorialStarDirection>,
    context: StarProjectionContext,
    geometry: CameraSessionGeometry,
): StarPredictionBatchResult {
    val intrinsics = geometry.intrinsics.intrinsics
    if (intrinsics.referenceSpace != CameraIntrinsicsReferenceSpace.ANALYSIS_BUFFER) {
        return StarPredictionBatchResult.IntrinsicsMappingUnavailable(
            IntrinsicsMappingUnavailableReason.PHYSICAL_SENSOR_REFERENCE_SPACE_UNSUPPORTED,
        )
    }

    val pinhole = PinholeProjectionModel.forGeometry(geometry)
    return StarPredictionBatchResult.Ready(stars.map { star -> projectOneStar(star, context, geometry, pinhole) })
}

private fun projectOneStar(
    star: EquatorialStarDirection,
    context: StarProjectionContext,
    geometry: CameraSessionGeometry,
    pinhole: PinholeProjectionModel,
): PredictedStarProjection {
    val localSky = equatorialToLocalSky(star, context)

    val displayDevice = worldToDeviceVector(rotationMatrix = geometry.pairedRotation.rotationMatrix, world = localSky)
    val displayOptical = DeviceToOpticalCameraTransform.apply(displayDevice)
    val bufferOptical =
        DisplayAlignedOpticalToBufferOpticalTransform.apply(
            displayOptical = displayOptical,
            rotationDegrees = geometry.frame.rotationDegrees,
        )
    val cameraProjection = projectBufferOpticalDirection(bufferOptical)

    return when (cameraProjection) {
        is CameraDirectionProjection.BehindCamera ->
            PredictedStarProjection(
                catalogIndex = star.catalogIndex,
                magnitude = star.magnitude,
                classification = PredictedStarClassification.BEHIND_CAMERA,
                cameraDirection = null,
                imagePoint = null,
                displayPoint = null,
            )

        is CameraDirectionProjection.InFront -> {
            val imagePoint = pinhole.project(cameraProjection.normalizedX, cameraProjection.normalizedY)
            val displayPoint = geometry.cropScaleTransform.imageToDisplay(imagePoint)
            // Same tolerance CropScaleTransform.isImagePointVisible defaults to, and for the same
            // reason: absorb sub-pixel float noise (e.g. an on-axis point landing at 1e-13 instead of
            // exactly 0.0 after the FOV-derived focal length's own rounding) without materially
            // widening the crop boundary. Using a bare 0.0 tolerance here (PixelRect.contains' own
            // default) would make a star classify as OUTSIDE_IMAGE based on floating-point rounding
            // direction rather than real geometry.
            val insideCrop = geometry.cropScaleTransform.sourceCrop.contains(imagePoint, CropScaleTransform.DEFAULT_VISIBILITY_TOLERANCE_PX)
            val visibleInViewport = geometry.cropScaleTransform.isImagePointVisible(imagePoint)

            val classification =
                when {
                    !insideCrop -> PredictedStarClassification.OUTSIDE_IMAGE
                    !visibleInViewport -> PredictedStarClassification.INSIDE_IMAGE_OUTSIDE_VIEWPORT
                    else -> PredictedStarClassification.VISIBLE_IN_VIEWPORT
                }

            PredictedStarProjection(
                catalogIndex = star.catalogIndex,
                magnitude = star.magnitude,
                classification = classification,
                cameraDirection =
                    CameraDirectionSnapshot(
                        cameraX = cameraProjection.cameraX,
                        cameraY = cameraProjection.cameraY,
                        cameraZ = cameraProjection.cameraZ,
                        normalizedX = cameraProjection.normalizedX,
                        normalizedY = cameraProjection.normalizedY,
                    ),
                imagePoint = imagePoint,
                displayPoint = displayPoint,
            )
        }
    }
}
