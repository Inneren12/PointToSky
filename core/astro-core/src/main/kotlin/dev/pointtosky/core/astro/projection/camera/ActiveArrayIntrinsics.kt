package dev.pointtosky.core.astro.projection.camera

/**
 * Validated pinhole intrinsics over Camera2's `SENSOR_INFO_ACTIVE_ARRAY_SIZE` pixel grid (CAM-2c
 * §3) — distinct from [dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel],
 * which is always in the *analysis-buffer* space. Never itself usable for projection:
 * [dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel.forGeometry]
 * requires an [CameraIntrinsicsReference.AnalysisBuffer]-referenced [CameraIntrinsics]; an
 * [ActiveArrayIntrinsics] value must first be mapped through the exact CameraX sensor-to-buffer
 * transform (see `mapActiveArrayIntrinsicsThroughMatrix` in `AnalysisBufferIntrinsicsMapping.kt`)
 * before it describes any particular analyzed buffer.
 *
 * ## Coordinate basis (CAM-2c fix round 2, §1) — `cxPx`/`cyPx` are in **sensor matrix space**, not
 * rectangle-local
 * `ImageInfo.getSensorToBufferTransformMatrix()`'s own documented domain is "the value of
 * `CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE`" — a `Rect(left, top, right, bottom)` whose
 * `left`/`top` are **not guaranteed to be zero** (a sensor's active array need not start at the
 * physical pixel array's own origin). [cxPx]/[cyPx] must therefore already be expressed in that same
 * absolute coordinate system — "sensor matrix space" — **before** being composed with the matrix
 * (`mapActiveArrayIntrinsicsThroughMatrix`); they are never rectangle-local (`[0, widthPx)`) unless
 * [coordinateOriginXPx]/[coordinateOriginYPx] both happen to be `0.0`. An earlier revision silently
 * assumed the active array always starts at `(0, 0)` — reading `LENS_INTRINSIC_CALIBRATION`'s `cx`/`cy`
 * directly, and defaulting the focal-length-derived centre to `(widthPx/2, heightPx/2)` — which is only
 * correct when the active array's own `left`/`top` are exactly zero. [coordinateOriginXPx]/
 * [coordinateOriginYPx] record where *this* rectangle's own top-left corner sits in that same sensor
 * matrix space (i.e. `SENSOR_INFO_ACTIVE_ARRAY_SIZE.left`/`.top`), so a caller/reviewer can verify
 * [cxPx]/[cyPx] were translated correctly rather than trusting an implicit, unstated convention.
 *
 * @property fxPx horizontal focal length in active-array pixels. Finite, strictly positive.
 * @property fyPx vertical focal length in active-array pixels. Finite, strictly positive.
 * @property cxPx principal point X, in **sensor matrix space** (see above) — i.e.
 *   `coordinateOriginXPx + <rectangle-local X>`, not rectangle-local by itself. Finite.
 * @property cyPx principal point Y, in sensor matrix space. Finite.
 * @property widthPx `SENSOR_INFO_ACTIVE_ARRAY_SIZE` width in pixels. Strictly positive.
 * @property heightPx `SENSOR_INFO_ACTIVE_ARRAY_SIZE` height in pixels. Strictly positive.
 * @property skewPx the pinhole model's skew term (CAM-2c fix §2/§3) — the full active-array intrinsic
 *   matrix is `K = [[fxPx, skewPx, cxPx], [0, fyPx, cyPx], [0, 0, 1]]`. `0.0` (the default) means "no
 *   skew known/assumed", exactly what [activeArrayIntrinsicsFromFocalLength] below always produces —
 *   that derivation has no skew term to recover. A non-zero value only ever comes from Camera2
 *   `LENS_INTRINSIC_CALIBRATION`'s own fifth element, and only when
 *   `dev.pointtosky.mobile.ar.camera.resolveAnalysisBufferIntrinsics` has already decided (against its
 *   own documented tolerance) that the value is small enough to trust rather than reject the
 *   calibrated source outright. Finite.
 * @property coordinateOriginXPx where this rectangle's own left edge sits in sensor matrix space —
 *   `SENSOR_INFO_ACTIVE_ARRAY_SIZE.left` (or the exactly-matching pre-correction rectangle's `left`,
 *   when calibration-derived — see `resolveAnalysisBufferIntrinsics`'s exact-rectangle-match check).
 *   `0.0` (the default) only when the active array genuinely starts at sensor matrix space's own
 *   origin. Diagnostics/documentation only — already folded into [cxPx], never added again.
 * @property coordinateOriginYPx the [coordinateOriginXPx] analogue for the top edge.
 */
data class ActiveArrayIntrinsics(
    val fxPx: Double,
    val fyPx: Double,
    val cxPx: Double,
    val cyPx: Double,
    val widthPx: Int,
    val heightPx: Int,
    val skewPx: Double = 0.0,
    val coordinateOriginXPx: Double = 0.0,
    val coordinateOriginYPx: Double = 0.0,
) {
    init {
        require(fxPx.isFinite() && fxPx > 0.0) { "fxPx must be finite and strictly positive; was $fxPx" }
        require(fyPx.isFinite() && fyPx > 0.0) { "fyPx must be finite and strictly positive; was $fyPx" }
        require(cxPx.isFinite()) { "cxPx must be finite; was $cxPx" }
        require(cyPx.isFinite()) { "cyPx must be finite; was $cyPx" }
        require(widthPx > 0) { "widthPx must be strictly positive; was $widthPx" }
        require(heightPx > 0) { "heightPx must be strictly positive; was $heightPx" }
        require(skewPx.isFinite()) { "skewPx must be finite; was $skewPx" }
        require(coordinateOriginXPx.isFinite()) { "coordinateOriginXPx must be finite; was $coordinateOriginXPx" }
        require(coordinateOriginYPx.isFinite()) { "coordinateOriginYPx must be finite; was $coordinateOriginYPx" }
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
 * mapping in `mapActiveArrayIntrinsicsThroughMatrix`.
 *
 * The principal point defaults to the active array's geometric centre, **in sensor matrix space**
 * (CAM-2c fix §1) — `coordinateOriginXPx + activeArrayWidthPx/2`, `coordinateOriginYPx +
 * activeArrayHeightPx/2` — when [principalPointXPx]/[principalPointYPx] is absent. [coordinateOriginXPx]/
 * [coordinateOriginYPx] default to `0.0`, reproducing the pre-fix behavior exactly for the common case
 * where the active array genuinely starts at sensor matrix space's own origin; a caller whose active
 * array does not (`SENSOR_INFO_ACTIVE_ARRAY_SIZE.left`/`.top` non-zero) must pass them explicitly, or
 * this default silently produces a rectangle-local, not sensor-matrix-space, centre — exactly the
 * defect this fix closes. This is always an explicit, documented **approximation**, never a
 * measurement, regardless of origin; callers must label the resulting quality accordingly (see
 * [CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT]) rather than treating a caller-supplied
 * principal point and this default the same way. A caller-supplied [principalPointXPx]/
 * [principalPointYPx] must likewise already be in sensor matrix space.
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
    coordinateOriginXPx: Double = 0.0,
    coordinateOriginYPx: Double = 0.0,
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
    require(coordinateOriginXPx.isFinite()) { "coordinateOriginXPx must be finite; was $coordinateOriginXPx" }
    require(coordinateOriginYPx.isFinite()) { "coordinateOriginYPx must be finite; was $coordinateOriginYPx" }

    return ActiveArrayIntrinsics(
        fxPx = focalLengthMm / sensorWidthMm * activeArrayWidthPx,
        fyPx = focalLengthMm / sensorHeightMm * activeArrayHeightPx,
        cxPx = principalPointXPx ?: (coordinateOriginXPx + activeArrayWidthPx / 2.0),
        cyPx = principalPointYPx ?: (coordinateOriginYPx + activeArrayHeightPx / 2.0),
        widthPx = activeArrayWidthPx,
        heightPx = activeArrayHeightPx,
        coordinateOriginXPx = coordinateOriginXPx,
        coordinateOriginYPx = coordinateOriginYPx,
    )
}
