package dev.pointtosky.core.astro.projection.camera.prediction

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry
import dev.pointtosky.core.astro.projection.camera.PixelPoint
import kotlin.math.tan

/**
 * A validated pinhole-camera projection model: pixel-space focal lengths and principal point over
 * one image coordinate space (CAM-2a §7).
 *
 * ## Which coordinate space?
 * [imageWidthPx]/[imageHeightPx] (and therefore [principalPointXPx]/[principalPointYPx] and the
 * pixel coordinates [project] returns) are in the **full analyzed-buffer** space: `frame.bufferWidthPx
 * ×frame.bufferHeightPx`, *unrotated* and *uncropped* — exactly
 * [dev.pointtosky.core.astro.projection.camera.CropScaleTransform.imageToDisplay]'s documented input
 * ("buffer space"), and exactly what
 * [dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata] itself documents
 * (`bufferWidthPx`/`bufferHeightPx` "are not swapped to account for rotationDegrees, and are not
 * assumed to equal any display/viewport size"). This is CAM-2a's chosen "Model A": project directly
 * into unrotated source-buffer coordinates, then let [CropScaleTransform.imageToDisplay]'s existing,
 * already-tested rotate→scale→offset pipeline carry the point the rest of the way to a display pixel.
 * The pinhole model here applies **no** rotation of its own — applying [forGeometry]'s
 * `frame.rotationDegrees` again here, on top of what `imageToDisplay` already does, would rotate the
 * point twice.
 *
 * @property focalLengthXPx horizontal focal length in buffer pixels. Finite, strictly positive.
 * @property focalLengthYPx vertical focal length in buffer pixels. Finite, strictly positive.
 * @property principalPointXPx principal point X in buffer pixels. Finite.
 * @property principalPointYPx principal point Y in buffer pixels. Finite.
 * @property imageWidthPx full analyzed-buffer width in pixels. Finite, strictly positive.
 * @property imageHeightPx full analyzed-buffer height in pixels. Finite, strictly positive.
 */
data class PinholeProjectionModel(
    val focalLengthXPx: Double,
    val focalLengthYPx: Double,
    val principalPointXPx: Double,
    val principalPointYPx: Double,
    val imageWidthPx: Double,
    val imageHeightPx: Double,
) {
    init {
        require(focalLengthXPx.isFinite() && focalLengthXPx > 0.0) {
            "focalLengthXPx must be finite and strictly positive; was $focalLengthXPx"
        }
        require(focalLengthYPx.isFinite() && focalLengthYPx > 0.0) {
            "focalLengthYPx must be finite and strictly positive; was $focalLengthYPx"
        }
        require(principalPointXPx.isFinite()) { "principalPointXPx must be finite; was $principalPointXPx" }
        require(principalPointYPx.isFinite()) { "principalPointYPx must be finite; was $principalPointYPx" }
        require(imageWidthPx.isFinite() && imageWidthPx > 0.0) {
            "imageWidthPx must be finite and strictly positive; was $imageWidthPx"
        }
        require(imageHeightPx.isFinite() && imageHeightPx > 0.0) {
            "imageHeightPx must be finite and strictly positive; was $imageHeightPx"
        }
    }

    /**
     * Projects normalized camera-plane coordinates ([CameraDirectionProjection.InFront.normalizedX]/
     * `normalizedY`) into buffer-space pixels: `u = fx·normalizedX + cx`, `v = fy·normalizedY + cy`.
     * Never clamped to [imageWidthPx]/[imageHeightPx] — a point outside the image is a valid,
     * meaningful result (see [dev.pointtosky.core.astro.projection.camera.PixelRect.contains] for the
     * separate classification step).
     */
    fun project(
        normalizedX: Double,
        normalizedY: Double,
    ): PixelPoint =
        PixelPoint(
            x = focalLengthXPx * normalizedX + principalPointXPx,
            y = focalLengthYPx * normalizedY + principalPointYPx,
        )

    companion object {
        /**
         * Builds the model for one [CameraSessionGeometry], deriving pixel focal lengths from
         * [CameraIntrinsicsResolution.intrinsics]' field-of-view degrees over the full analyzed-buffer
         * size ([CameraSessionGeometry.cropScaleTransform]'s `sourceBufferSize` — equal to
         * `frame.bufferWidthPx`/`bufferHeightPx` by [CameraSessionGeometry]'s own invariant):
         * ```text
         * fx = imageWidthPx  / (2 · tan(horizontalFovDeg / 2))
         * fy = imageHeightPx / (2 · tan(verticalFovDeg   / 2))
         * ```
         * This applies uniformly whether [geometry].intrinsics is
         * [CameraIntrinsicsResolution.Resolved] (a real per-device FOV) or
         * [CameraIntrinsicsResolution.LegacyFallback] (the hardcoded 56° vertical / aspect-derived
         * horizontal default) — [dev.pointtosky.core.astro.projection.camera.CameraIntrinsics] never
         * stores a pixel focal length directly in either case, only a FOV in degrees, so both sources
         * go through the same derivation here.
         *
         * The principal point defaults to the buffer's geometric center
         * (`imageWidthPx/2`, `imageHeightPx/2`) when
         * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsics.principalPointXPx]/
         * `principalPointYPx` is absent — which is always, as of CAM-1b (see
         * `docs/camera_coordinate_calibration_contract.md` §3.4).
         */
        fun forGeometry(geometry: CameraSessionGeometry): PinholeProjectionModel {
            val intrinsics = geometry.intrinsics.intrinsics
            val widthPx = geometry.cropScaleTransform.sourceBufferSize.width
            val heightPx = geometry.cropScaleTransform.sourceBufferSize.height

            val fx = widthPx / (2.0 * tan(Math.toRadians(intrinsics.horizontalFovDeg) / 2.0))
            val fy = heightPx / (2.0 * tan(Math.toRadians(intrinsics.verticalFovDeg) / 2.0))
            val cx = intrinsics.principalPointXPx ?: (widthPx / 2.0)
            val cy = intrinsics.principalPointYPx ?: (heightPx / 2.0)

            return PinholeProjectionModel(
                focalLengthXPx = fx,
                focalLengthYPx = fy,
                principalPointXPx = cx,
                principalPointYPx = cy,
                imageWidthPx = widthPx,
                imageHeightPx = heightPx,
            )
        }
    }
}
