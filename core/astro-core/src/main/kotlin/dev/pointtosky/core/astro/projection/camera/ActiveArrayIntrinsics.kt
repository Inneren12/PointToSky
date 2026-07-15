package dev.pointtosky.core.astro.projection.camera

/**
 * Validated pinhole intrinsics over Camera2's `SENSOR_INFO_ACTIVE_ARRAY_SIZE` pixel grid (CAM-2c
 * §3) — distinct from [dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel],
 * which is always in the *analysis-buffer* space. Never itself usable for projection:
 * [dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel.forGeometry]
 * requires an [CameraIntrinsicsReference.AnalysisBuffer]-referenced [CameraIntrinsics]; an
 * [ActiveArrayIntrinsics] value must first be mapped through the exact CameraX sensor-to-buffer
 * transform (see `mapActiveArrayIntrinsicsToAnalysisBuffer` in `AnalysisBufferIntrinsicsMapping.kt`)
 * before it describes any particular analyzed buffer.
 *
 * @property fxPx horizontal focal length in active-array pixels. Finite, strictly positive.
 * @property fyPx vertical focal length in active-array pixels. Finite, strictly positive.
 * @property cxPx principal point X in active-array pixels. Finite.
 * @property cyPx principal point Y in active-array pixels. Finite.
 * @property widthPx `SENSOR_INFO_ACTIVE_ARRAY_SIZE` width in pixels. Strictly positive.
 * @property heightPx `SENSOR_INFO_ACTIVE_ARRAY_SIZE` height in pixels. Strictly positive.
 */
data class ActiveArrayIntrinsics(
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
 * Derives [ActiveArrayIntrinsics] from Camera2 `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` +
 * `SENSOR_INFO_PHYSICAL_SIZE` + `SENSOR_INFO_ACTIVE_ARRAY_SIZE` (CAM-2c §3), when no calibrated
 * `LENS_INTRINSIC_CALIBRATION` is usable:
 * ```text
 * fxActive = focalLengthMm / physicalSensorWidthMm  * activeArrayWidthPx
 * fyActive = focalLengthMm / physicalSensorHeightMm * activeArrayHeightPx
 * ```
 * This is the same physical relationship [horizontalFovDeg]/[verticalFovDeg] already encode as an
 * angle (`fov = 2 atan(dim / (2 f))`); expressing it directly in active-array pixels here, rather
 * than converting through degrees, avoids a redundant trig round-trip before the buffer-space
 * mapping in `mapActiveArrayIntrinsicsToAnalysisBuffer`.
 *
 * The principal point defaults to the active array's geometric centre
 * (`activeArrayWidthPx/2`, `activeArrayHeightPx/2`) when [principalPointXPx]/[principalPointYPx] is
 * absent — an explicit, documented **approximation**, never a measurement; callers must label the
 * resulting quality accordingly (see [CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT]) rather
 * than treating a caller-supplied principal point and this default the same way.
 *
 * @throws IllegalArgumentException if any input is non-finite/non-positive, or the resulting
 *   intrinsics fail [ActiveArrayIntrinsics]'s own validation.
 */
fun activeArrayIntrinsicsFromFocalLength(
    focalLengthMm: Double,
    sensorWidthMm: Double,
    sensorHeightMm: Double,
    activeArrayWidthPx: Int,
    activeArrayHeightPx: Int,
    principalPointXPx: Double? = null,
    principalPointYPx: Double? = null,
): ActiveArrayIntrinsics {
    require(focalLengthMm.isFinite() && focalLengthMm > 0.0) {
        "focalLengthMm must be finite and strictly positive; was $focalLengthMm"
    }
    require(sensorWidthMm.isFinite() && sensorWidthMm > 0.0) {
        "sensorWidthMm must be finite and strictly positive; was $sensorWidthMm"
    }
    require(sensorHeightMm.isFinite() && sensorHeightMm > 0.0) {
        "sensorHeightMm must be finite and strictly positive; was $sensorHeightMm"
    }
    require(activeArrayWidthPx > 0) { "activeArrayWidthPx must be strictly positive; was $activeArrayWidthPx" }
    require(activeArrayHeightPx > 0) { "activeArrayHeightPx must be strictly positive; was $activeArrayHeightPx" }

    return ActiveArrayIntrinsics(
        fxPx = focalLengthMm / sensorWidthMm * activeArrayWidthPx,
        fyPx = focalLengthMm / sensorHeightMm * activeArrayHeightPx,
        cxPx = principalPointXPx ?: (activeArrayWidthPx / 2.0),
        cyPx = principalPointYPx ?: (activeArrayHeightPx / 2.0),
        widthPx = activeArrayWidthPx,
        heightPx = activeArrayHeightPx,
    )
}
