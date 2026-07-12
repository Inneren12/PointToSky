package dev.pointtosky.mobile.ar.camera

import androidx.camera.core.ImageProxy
import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata

/**
 * Metadata-only view of one analyzed camera frame (CAM-1c), decoupled from `ImageProxy` mechanics
 * so [toCameraFrameMetadata] and [CameraFrameAnalyzer] can be unit-tested with a plain fake — no
 * real camera, no `ImageProxy` mock. Mirrors [dev.pointtosky.mobile.ar.camera.CameraCharacteristicsSource]
 * from CAM-1b: production code adapts the real CameraX type ([ImageProxyFrameMetadataSource]),
 * resolution/mapping logic is tested against the interface.
 *
 * Implementations must expose only metadata already available on `ImageProxy` without touching
 * `planes`, `image`, or any pixel buffer/row/stride.
 */
interface CloseableFrameMetadataSource {
    val timestampNanos: Long
    val widthPx: Int
    val heightPx: Int
    val rotationDegrees: Int
    val cropRectLeftPx: Int?
    val cropRectTopPx: Int?
    val cropRectRightPx: Int?
    val cropRectBottomPx: Int?

    /** Releases the underlying frame. Callers must invoke this exactly once, always in `finally`. */
    fun close()
}

/**
 * Pure mapping from raw [CloseableFrameMetadataSource] fields to a validated [CameraFrameMetadata].
 * Separated from [ImageProxyFrameMetadataSource] so this mapping — and the [CameraFrameMetadata]
 * validation it triggers — is testable with plain values, without a real `ImageProxy`.
 */
fun CloseableFrameMetadataSource.toCameraFrameMetadata(): CameraFrameMetadata =
    CameraFrameMetadata(
        timestampNanos = timestampNanos,
        bufferWidthPx = widthPx,
        bufferHeightPx = heightPx,
        rotationDegrees = rotationDegrees,
        cropRectLeftPx = cropRectLeftPx,
        cropRectTopPx = cropRectTopPx,
        cropRectRightPx = cropRectRightPx,
        cropRectBottomPx = cropRectBottomPx,
    )

/**
 * Production [CloseableFrameMetadataSource] wrapping a real [ImageProxy] (CAM-1c).
 *
 * Reads exactly [ImageProxy.imageInfo] (`timestamp`, `rotationDegrees`), [ImageProxy.getWidth],
 * [ImageProxy.getHeight], and [ImageProxy.getCropRect] — never [ImageProxy.getPlanes],
 * `ImageProxy.getImage()`, or any pixel row/stride. [close] delegates to [ImageProxy.close].
 */
internal class ImageProxyFrameMetadataSource(
    private val imageProxy: ImageProxy,
) : CloseableFrameMetadataSource {
    override val timestampNanos: Long get() = imageProxy.imageInfo.timestamp
    override val widthPx: Int get() = imageProxy.width
    override val heightPx: Int get() = imageProxy.height
    override val rotationDegrees: Int get() = imageProxy.imageInfo.rotationDegrees
    override val cropRectLeftPx: Int get() = imageProxy.cropRect.left
    override val cropRectTopPx: Int get() = imageProxy.cropRect.top
    override val cropRectRightPx: Int get() = imageProxy.cropRect.right
    override val cropRectBottomPx: Int get() = imageProxy.cropRect.bottom

    override fun close() = imageProxy.close()
}
