package dev.pointtosky.core.astro.projection.camera

/**
 * Pure, Android-independent metadata for one CameraX `ImageAnalysis` frame (CAM-1c).
 *
 * This is a metadata-only contract: it never carries pixel data, a `Y/U/V` buffer, or an
 * `ImageProxy`/`Image` reference. Production code adapts a real `ImageProxy` into this shape (see
 * `dev.pointtosky.mobile.ar.camera.ImageProxyFrameMetadataSource` in `:mobile`) without reading
 * `planes`, `image`, or any pixel row/stride.
 *
 * [timestampNanos] is exactly `ImageProxy.imageInfo.timestamp` — camera-clock nanoseconds, **not**
 * wall-clock time. It must not be compared to `System.currentTimeMillis()`. CAM-1c makes no claim
 * that it shares a clock base with `SensorEvent.timestamp`; matching/pairing the two is CAM-1d's
 * job, not implemented here.
 *
 * [bufferWidthPx]/[bufferHeightPx] are the analyzed buffer's own dimensions — they are **not**
 * swapped to account for [rotationDegrees], and they are not assumed to equal any display/viewport
 * size. The optional crop-rect fields, when present, describe `ImageProxy.cropRect` as plain
 * integers (never an Android `Rect`) and must together describe a region ordered and contained
 * within the buffer.
 *
 * @property timestampNanos frame timestamp in nanoseconds, camera-clock origin. Must be
 *   non-negative.
 * @property bufferWidthPx analyzed buffer width in pixels. Must be strictly positive.
 * @property bufferHeightPx analyzed buffer height in pixels. Must be strictly positive.
 * @property rotationDegrees `ImageProxy.imageInfo.rotationDegrees`. Must be one of `0`, `90`,
 *   `180`, `270`.
 * @property cropRectLeftPx `ImageProxy.cropRect.left`, when represented. Either all four crop
 *   fields are present or all four are absent.
 * @property cropRectTopPx `ImageProxy.cropRect.top`, when represented.
 * @property cropRectRightPx `ImageProxy.cropRect.right`, when represented.
 * @property cropRectBottomPx `ImageProxy.cropRect.bottom`, when represented.
 * @property sensorToBufferTransform (CAM-2c, fix §1) `ImageProxy.imageInfo.getSensorToBufferTransformMatrix()`,
 *   converted to a plain, Android-independent [SensorToBufferMatrix3] — the real per-frame mapping
 *   from Camera2 `SENSOR_INFO_ACTIVE_ARRAY_SIZE` pixel coordinates to *this exact frame's own*
 *   [bufferWidthPx] × [bufferHeightPx] buffer, preserving all 9 reported values (see
 *   `dev.pointtosky.mobile.ar.camera.ImageProxyFrameMetadataSource`) — never collapsed to an
 *   axis-aligned-only approximation. `null` only when the underlying matrix is entirely unavailable.
 *   A present-but-geometrically-unsupported matrix (see [classifySensorToBufferMatrix]) is still
 *   carried here in full; it is [dev.pointtosky.mobile.ar.camera.resolveAnalysisBufferIntrinsics]'s
 *   job to classify and reject it explicitly, never this type's. **Not** the same coordinate space as
 *   [cropRectLeftPx]/etc above, which are already in *this frame's own buffer* pixel coordinates;
 *   see [ActiveArraySensorCropRegion]'s KDoc for why the two must never be conflated.
 */
data class CameraFrameMetadata(
    val timestampNanos: Long,
    val bufferWidthPx: Int,
    val bufferHeightPx: Int,
    val rotationDegrees: Int,
    val cropRectLeftPx: Int? = null,
    val cropRectTopPx: Int? = null,
    val cropRectRightPx: Int? = null,
    val cropRectBottomPx: Int? = null,
    val sensorToBufferTransform: SensorToBufferMatrix3? = null,
) {
    init {
        require(timestampNanos >= 0L) { "timestampNanos must be non-negative; was $timestampNanos" }
        require(bufferWidthPx > 0) { "bufferWidthPx must be positive; was $bufferWidthPx" }
        require(bufferHeightPx > 0) { "bufferHeightPx must be positive; was $bufferHeightPx" }
        require(rotationDegrees in VALID_ROTATIONS_DEG) {
            "rotationDegrees must be one of $VALID_ROTATIONS_DEG; was $rotationDegrees"
        }

        val cropFields = listOf(cropRectLeftPx, cropRectTopPx, cropRectRightPx, cropRectBottomPx)
        val presentCount = cropFields.count { it != null }
        require(presentCount == 0 || presentCount == cropFields.size) {
            "crop rect fields must be all present or all absent; was $cropFields"
        }
        if (presentCount == cropFields.size) {
            val left = requireNotNull(cropRectLeftPx)
            val top = requireNotNull(cropRectTopPx)
            val right = requireNotNull(cropRectRightPx)
            val bottom = requireNotNull(cropRectBottomPx)
            require(left >= 0 && top >= 0) {
                "crop rect left/top must be non-negative; was left=$left, top=$top"
            }
            require(left < right && top < bottom) {
                "crop rect must be ordered (left < right, top < bottom); was left=$left, top=$top, " +
                    "right=$right, bottom=$bottom"
            }
            require(right <= bufferWidthPx && bottom <= bufferHeightPx) {
                "crop rect must be within the buffer (${bufferWidthPx}x$bufferHeightPx); " +
                    "was right=$right, bottom=$bottom"
            }
        }
    }

    private companion object {
        val VALID_ROTATIONS_DEG = setOf(0, 90, 180, 270)
    }
}
