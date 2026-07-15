package dev.pointtosky.core.astro.projection.camera

/**
 * An axis-aligned affine map from Camera2 `SENSOR_INFO_ACTIVE_ARRAY_SIZE` pixel coordinates to one
 * CameraX `ImageAnalysis` buffer's own pixel coordinates (CAM-2c §5):
 * `bufferX = activeX * scaleX + translateXPx`, `bufferY = activeY * scaleY + translateYPx`.
 *
 * Mirrors exactly the real, stable CameraX contract `ImageInfo.getSensorToBufferTransformMatrix()`
 * documents: "a mapping from sensor coordinates to buffer coordinates, ... from the value of
 * `CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE` to `(0, 0, image.getWidth, image.getHeight)`."
 * Deliberately narrower than a general 3x3 `android.graphics.Matrix`: Camera2's own crop+scale
 * pipeline (`SCALER_CROP_REGION`, then scale-to-output-resolution) never introduces rotation or
 * skew, only an axis-aligned scale+translate — `:mobile`'s extraction from the real
 * `android.graphics.Matrix` validates that assumption (skew ≈ 0) before constructing this type, so
 * a genuinely rotated/skewed matrix can never be silently misrepresented as one of these. This type
 * carries no rotation of its own; `CameraFrameMetadata.rotationDegrees` remains the one place
 * rotation is recorded, applied exactly once downstream by `CropScaleTransform`/
 * `DisplayAlignedOpticalToBufferOpticalTransform` — never here, and never twice.
 *
 * @property scaleX buffer-pixels-per-active-array-pixel along X. Finite, strictly positive.
 * @property scaleY buffer-pixels-per-active-array-pixel along Y. Finite, strictly positive.
 * @property translateXPx buffer-space X offset. Finite.
 * @property translateYPx buffer-space Y offset. Finite.
 */
data class SensorToBufferTransform(
    val scaleX: Double,
    val scaleY: Double,
    val translateXPx: Double,
    val translateYPx: Double,
) {
    init {
        require(scaleX.isFinite() && scaleX > 0.0) { "scaleX must be finite and strictly positive; was $scaleX" }
        require(scaleY.isFinite() && scaleY > 0.0) { "scaleY must be finite and strictly positive; was $scaleY" }
        require(translateXPx.isFinite()) { "translateXPx must be finite; was $translateXPx" }
        require(translateYPx.isFinite()) { "translateYPx must be finite; was $translateYPx" }
    }
}

/**
 * A crop region expressed in Camera2 `SENSOR_INFO_ACTIVE_ARRAY_SIZE` pixel coordinates (CAM-2c §4)
 * — e.g. the region [SensorToBufferTransform] maps onto one analysis buffer.
 *
 * **Not** the same coordinate space as [CameraFrameMetadata]'s own `cropRectLeftPx`/`cropRectTopPx`/
 * `cropRectRightPx`/`cropRectBottomPx` fields, which are already in that frame's own *buffer* pixel
 * coordinates (see that class's KDoc: they are constrained to lie within `bufferWidthPx`/
 * `bufferHeightPx`). Conflating the two — treating `ImageProxy.cropRect` as if it described an
 * active-array-space crop — is exactly the mistake this type's distinct name guards against; see
 * `dev.pointtosky.mobile.ar.camera.ImageProxyFrameMetadataSource`'s KDoc for why `ImageProxy.cropRect`
 * is never used as this type's source in this codebase's current CameraX binding path.
 *
 * @property leftPx inclusive left edge (smaller x), active-array pixels.
 * @property topPx inclusive top edge (smaller y), active-array pixels.
 * @property rightPx exclusive-by-convention right edge (larger x), active-array pixels.
 * @property bottomPx exclusive-by-convention bottom edge (larger y), active-array pixels.
 */
data class ActiveArraySensorCropRegion(
    val leftPx: Double,
    val topPx: Double,
    val rightPx: Double,
    val bottomPx: Double,
) {
    init {
        require(leftPx.isFinite() && topPx.isFinite() && rightPx.isFinite() && bottomPx.isFinite()) {
            "all crop-region edges must be finite; was ($leftPx, $topPx, $rightPx, $bottomPx)"
        }
        require(leftPx < rightPx) {
            "crop region must be ordered horizontally (leftPx < rightPx); was leftPx=$leftPx, rightPx=$rightPx"
        }
        require(topPx < bottomPx) {
            "crop region must be ordered vertically (topPx < bottomPx); was topPx=$topPx, bottomPx=$bottomPx"
        }
    }

    /** Width in active-array pixels, always strictly positive by the ordering invariant. */
    val widthPx: Double get() = rightPx - leftPx

    /** Height in active-array pixels, always strictly positive by the ordering invariant. */
    val heightPx: Double get() = bottomPx - topPx
}

/**
 * Inverts this [SensorToBufferTransform] into the [ActiveArraySensorCropRegion] it maps onto a
 * buffer of exactly [bufferWidthPx] × [bufferHeightPx] pixels — the active-array-space region whose
 * `(0,0,widthPx,heightPx)` image maps 1:1 onto that exact buffer. Evidence-based: derived from the
 * real per-frame transform CameraX reports, never guessed from matching dimensions alone (CAM-2c §5).
 *
 * @throws IllegalArgumentException if [bufferWidthPx]/[bufferHeightPx] is not strictly positive, or
 *   the resulting region fails [ActiveArraySensorCropRegion]'s own validation (e.g. a degenerate
 *   transform that would invert to a zero-or-negative-size region).
 */
fun SensorToBufferTransform.toActiveArraySensorCropRegion(
    bufferWidthPx: Int,
    bufferHeightPx: Int,
): ActiveArraySensorCropRegion {
    require(bufferWidthPx > 0) { "bufferWidthPx must be strictly positive; was $bufferWidthPx" }
    require(bufferHeightPx > 0) { "bufferHeightPx must be strictly positive; was $bufferHeightPx" }

    val leftPx = -translateXPx / scaleX
    val topPx = -translateYPx / scaleY
    return ActiveArraySensorCropRegion(
        leftPx = leftPx,
        topPx = topPx,
        rightPx = leftPx + bufferWidthPx / scaleX,
        bottomPx = topPx + bufferHeightPx / scaleY,
    )
}

/**
 * The final calibrated pinhole intrinsics over one exact analysis buffer (CAM-2c §4) — buffer-space
 * `fx`/`fy`/`cx`/`cy`, before conversion to the FOV-based [CameraIntrinsics] contract (see
 * [toCameraIntrinsics]).
 *
 * @property fxPx horizontal focal length in buffer pixels. Finite, strictly positive.
 * @property fyPx vertical focal length in buffer pixels. Finite, strictly positive.
 * @property cxPx principal point X in buffer pixels. Finite.
 * @property cyPx principal point Y in buffer pixels. Finite.
 * @property widthPx the exact analysis buffer width these intrinsics apply to. Strictly positive.
 * @property heightPx the exact analysis buffer height these intrinsics apply to. Strictly positive.
 */
data class AnalysisBufferIntrinsicsValues(
    val fxPx: Double,
    val fyPx: Double,
    val cxPx: Double,
    val cyPx: Double,
    val widthPx: Int,
    val heightPx: Int,
) {
    init {
        require(fxPx.isFinite() && fxPx > 0.0) { "fxPx must be finite and strictly positive; was $fxPx" }
        require(fyPx.isFinite() && fyPx > 0.0) { "fyPx must be finite and strictly positive; was $fyPx" }
        require(cxPx.isFinite()) { "cxPx must be finite; was $cxPx" }
        require(cyPx.isFinite()) { "cyPx must be finite; was $cyPx" }
        require(widthPx > 0) { "widthPx must be strictly positive; was $widthPx" }
        require(heightPx > 0) { "heightPx must be strictly positive; was $heightPx" }
    }
}

/**
 * Maps [active] intrinsics through [cropRegion] (CAM-2c §4), then scales crop-space intrinsics to
 * the exact [bufferWidthPx] × [bufferHeightPx] analysis buffer:
 * ```text
 * cxCrop = cxActive - cropRegion.leftPx
 * cyCrop = cyActive - cropRegion.topPx
 * fxCrop = fxActive
 * fyCrop = fyActive
 *
 * sx = bufferWidthPx  / cropRegion.widthPx
 * sy = bufferHeightPx / cropRegion.heightPx
 *
 * fxBuffer = fxCrop * sx
 * fyBuffer = fyCrop * sy
 * cxBuffer = cxCrop * sx
 * cyBuffer = cyCrop * sy
 * ```
 * No rotation is applied here — see [SensorToBufferTransform]'s KDoc on rotation ownership.
 *
 * @throws IllegalArgumentException if [bufferWidthPx]/[bufferHeightPx] is not strictly positive, or
 *   [cropRegion] does not lie within [active]'s own `widthPx`/`heightPx` bounds (an "invalid crop" —
 *   never silently clamped to the active array).
 */
fun mapActiveArrayIntrinsicsToAnalysisBuffer(
    active: ActiveArrayIntrinsics,
    cropRegion: ActiveArraySensorCropRegion,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
): AnalysisBufferIntrinsicsValues {
    require(bufferWidthPx > 0) { "bufferWidthPx must be strictly positive; was $bufferWidthPx" }
    require(bufferHeightPx > 0) { "bufferHeightPx must be strictly positive; was $bufferHeightPx" }
    require(cropRegion.leftPx >= 0.0 && cropRegion.topPx >= 0.0) {
        "cropRegion must lie within the active array (leftPx/topPx >= 0); was $cropRegion"
    }
    require(cropRegion.rightPx <= active.widthPx && cropRegion.bottomPx <= active.heightPx) {
        "cropRegion must lie within the active array (${active.widthPx}x${active.heightPx}); was $cropRegion"
    }

    val cxCrop = active.cxPx - cropRegion.leftPx
    val cyCrop = active.cyPx - cropRegion.topPx
    val sx = bufferWidthPx / cropRegion.widthPx
    val sy = bufferHeightPx / cropRegion.heightPx

    return AnalysisBufferIntrinsicsValues(
        fxPx = active.fxPx * sx,
        fyPx = active.fyPx * sy,
        cxPx = cxCrop * sx,
        cyPx = cyCrop * sy,
        widthPx = bufferWidthPx,
        heightPx = bufferHeightPx,
    )
}

/**
 * Converts these buffer-space pinhole values into the FOV-based [CameraIntrinsics] contract CAM-2a
 * already consumes, with `source = `[CameraIntrinsicsSource.CAMERA_CHARACTERISTICS]` and
 * `reference = `[CameraIntrinsicsReference.AnalysisBuffer]`(`[widthPx]`, `[heightPx]`)`.
 *
 * Reuses [fovDegFromFocalLength] for the `fx`/`fy` → FOV conversion — the exact inverse of
 * `dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel.forGeometry`'s own
 * `fx = width / (2 tan(hFov/2))`, so the round trip through this [CameraIntrinsics] value is lossless
 * up to ordinary floating-point precision (`tan(atan(x)) == x`). [fovDegFromFocalLength] is
 * unit-agnostic (a pure ratio), so passing pixel dimensions where its parameter names say
 * millimetres is intentional, not a unit mismatch.
 *
 * @param focalLengthMm the real `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` value used to derive [active]'s
 *   `fx`/`fy` (or, when [quality] is [CameraIntrinsicsQuality.CALIBRATED], the same physical
 *   metadata recorded alongside `LENS_INTRINSIC_CALIBRATION`) — carried through only for
 *   [CameraIntrinsics]'s own optional physical-dimension fields, never re-derived here.
 * @throws IllegalArgumentException if the resulting [CameraIntrinsics] fails its own validation
 *   (e.g. a degenerate mapping producing an out-of-range FOV or a negative principal point).
 */
fun AnalysisBufferIntrinsicsValues.toCameraIntrinsics(
    focalLengthMm: Double,
    sensorWidthMm: Double,
    sensorHeightMm: Double,
    quality: CameraIntrinsicsQuality,
): CameraIntrinsics =
    CameraIntrinsics(
        horizontalFovDeg = fovDegFromFocalLength(widthPx.toDouble(), fxPx),
        verticalFovDeg = fovDegFromFocalLength(heightPx.toDouble(), fyPx),
        focalLengthMm = focalLengthMm,
        sensorWidthMm = sensorWidthMm,
        sensorHeightMm = sensorHeightMm,
        principalPointXPx = cxPx,
        principalPointYPx = cyPx,
        source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
        reference = CameraIntrinsicsReference.AnalysisBuffer(widthPx, heightPx),
        quality = quality,
    )
