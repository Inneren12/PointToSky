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
 * ## Coordinate basis (CAM-2c fix round 3, §P1) — `cxPx`/`cyPx` are **active-array-local**, never
 * translated by `SENSOR_INFO_ACTIVE_ARRAY_SIZE.left`/`.top`
 * A prior revision of this fix (round 2) treated [cxPx]/[cyPx] as absolute coordinates within the
 * full pixel array and added `SENSOR_INFO_ACTIVE_ARRAY_SIZE.left`/`.top` before composing with the
 * sensor-to-buffer matrix. That was itself a bug: Android's own Camera2 contract is explicit that
 * pixel-coordinate metadata *within* the active array is expressed **relative to that rectangle's
 * own top-left**, not the full pixel array's origin. Two independent, authoritative statements of
 * this (both quoted verbatim from AOSP's `system/media/camera/docs/metadata_definitions.xml`, which
 * generates the official `CameraCharacteristics` Javadoc):
 *
 * - `SENSOR_INFO_ACTIVE_ARRAY_SIZE`'s own details: *"The coordinate system for most other keys that
 *   list pixel coordinates, including `android.scaler.cropRegion`, is defined relative to the active
 *   array rectangle given in this field, with `(0, 0)` being the top-left of this rectangle."*
 * - `LENS_INTRINSIC_CALIBRATION`'s own details: *"the coordinate system for this transform is the
 *   `android.sensor.info.preCorrectionActiveArraySize` system, where `(0,0)` is the top-left of the
 *   `preCorrectionActiveArraySize` rectangle."* (and, after distortion correction, adjusted "to be in
 *   the `android.sensor.info.activeArraySize` coordinate system (where `(0, 0)` is the top-left of the
 *   `activeArraySize` rectangle)").
 *
 * `SENSOR_INFO_ACTIVE_ARRAY_SIZE`/`SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE` *themselves* — the
 * `Rect`s' own `left`/`top`/`right`/`bottom` — **are** reported relative to the full pixel array (see
 * `preCorrectionActiveArraySize`'s own details: *"This rectangle is defined relative to the full pixel
 * array; `(0,0)` is the top-left of the full pixel array"*), but that full-array placement is never
 * folded into any *other* key's own pixel coordinates, including [cxPx]/[cyPx] here — see
 * `ActiveArrayRect` (`AnalysisBufferIntrinsicsMapping.kt`) for where that full-array rectangle is
 * still tracked (diagnostics and the exact pre-correction/active four-edge match check only).
 * `ImageInfo.getSensorToBufferTransformMatrix()`'s own documented domain — "the value of
 * `CameraCharacteristics#SENSOR_INFO_ACTIVE_ARRAY_SIZE`" — is consistent only with this same
 * rectangle-local convention: every other Camera2/CameraX contract that names an active-array
 * coordinate system uses it, and the matrix's own worked example (mapping a `W`×`H` active array
 * onto a buffer with no absolute-offset term anywhere in the documented contract) never mentions the
 * rectangle's placement within the larger sensor. [cxPx]/[cyPx] must therefore already be
 * active-array-local **before** being composed with the matrix (`mapActiveArrayIntrinsicsThroughMatrix`)
 * — never translated by `SENSOR_INFO_ACTIVE_ARRAY_SIZE.left`/`.top`.
 *
 * @property fxPx horizontal focal length in sensor pixels (the active array and the full pixel array
 *   share the same underlying pixel pitch — only the coordinate domain differs; see
 *   [activeArrayIntrinsicsFromFocalLength]'s KDoc, CAM-2c fix §P2, for why this is derived from
 *   `SENSOR_INFO_PIXEL_ARRAY_SIZE`, never `SENSOR_INFO_ACTIVE_ARRAY_SIZE`, when focal-length-derived).
 *   Finite, strictly positive.
 * @property fyPx vertical focal length in sensor pixels, the [fxPx] analogue. Finite, strictly
 *   positive.
 * @property cxPx principal point X, active-array-local (`(0, 0)` at this rectangle's own top-left —
 *   see above). Finite.
 * @property cyPx principal point Y, active-array-local. Finite.
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
 */
data class ActiveArrayIntrinsics(
    val fxPx: Double,
    val fyPx: Double,
    val cxPx: Double,
    val cyPx: Double,
    val widthPx: Int,
    val heightPx: Int,
    val skewPx: Double = 0.0,
) {
    init {
        require(fxPx.isFinite() && fxPx > 0.0) { "fxPx must be finite and strictly positive; was $fxPx" }
        require(fyPx.isFinite() && fyPx > 0.0) { "fyPx must be finite and strictly positive; was $fyPx" }
        require(cxPx.isFinite()) { "cxPx must be finite; was $cxPx" }
        require(cyPx.isFinite()) { "cyPx must be finite; was $cyPx" }
        require(widthPx > 0) { "widthPx must be strictly positive; was $widthPx" }
        require(heightPx > 0) { "heightPx must be strictly positive; was $heightPx" }
        require(skewPx.isFinite()) { "skewPx must be finite; was $skewPx" }
    }
}

/**
 * Derives [ActiveArrayIntrinsics] from Camera2 `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` +
 * `SENSOR_INFO_PHYSICAL_SIZE` + `SENSOR_INFO_PIXEL_ARRAY_SIZE` (CAM-2c §3; corrected fix §P2), when
 * no calibrated `LENS_INTRINSIC_CALIBRATION` is usable:
 * ```text
 * fxPixelGrid = focalLengthMm / physicalSensorWidthMm  * pixelArrayWidthPx
 * fyPixelGrid = focalLengthMm / physicalSensorHeightMm * pixelArrayHeightPx
 * ```
 * **`fx`/`fy` are derived from the *full pixel array*, never the active array (CAM-2c fix §P2).**
 * `SENSOR_INFO_PHYSICAL_SIZE` describes the physical millimetres the *entire* pixel grid
 * (`SENSOR_INFO_PIXEL_ARRAY_SIZE`) spans — including any optically black or otherwise inactive
 * border pixels `SENSOR_INFO_ACTIVE_ARRAY_SIZE` excludes. Pixel pitch (`mm per pixel`, the physical
 * quantity `fx`/`fy` actually encode) is therefore `physicalSizeMm / pixelArrayPx`, not
 * `physicalSizeMm / activeArrayPx` — an earlier revision of this function divided by
 * [activeArrayWidthPx]/[activeArrayHeightPx] instead, which **overestimates** pixel pitch (and so
 * underestimates `fx`/`fy` in pixels, producing an overly wide computed FOV) whenever the active
 * array is smaller than the full pixel array. This is the same physical relationship
 * [horizontalFovDeg]/[verticalFovDeg] already encode as an angle (`fov = 2 atan(dim / (2 f))`);
 * expressing it directly in pixels here, rather than converting through degrees, avoids a redundant
 * trig round-trip before the buffer-space mapping in `mapActiveArrayIntrinsicsThroughMatrix`.
 *
 * [activeArrayWidthPx]/[activeArrayHeightPx] play a **different** role: they define the
 * active-array-local coordinate domain [ActiveArrayIntrinsics.widthPx]/`heightPx` describe, and the
 * default geometric-centre principal-point approximation below — never pixel pitch. The active array
 * and pixel array share the same underlying sensor pixel grid (a pixel is the same physical size in
 * both), so an `fx`/`fy` derived from the full pixel array is exactly as valid a description of
 * active-array-local pixels as it is of full-pixel-array ones — only the coordinate origin and domain
 * differ between the two, not the pixel pitch itself.
 *
 * The principal point defaults to the active array's own geometric centre, **active-array-local**
 * (CAM-2c fix round 3, §P1) — `activeArrayWidthPx/2`, `activeArrayHeightPx/2` — when
 * [principalPointXPx]/[principalPointYPx] is absent; never translated by
 * `SENSOR_INFO_ACTIVE_ARRAY_SIZE.left`/`.top` (see [ActiveArrayIntrinsics]'s own KDoc for why). This
 * is always an explicit, documented **approximation**, never a measurement; callers must label the
 * resulting quality accordingly (see [CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT]) rather
 * than treating a caller-supplied principal point and this default the same way. A caller-supplied
 * [principalPointXPx]/[principalPointYPx] must likewise already be active-array-local.
 *
 * @throws IllegalArgumentException if any input is non-finite/non-positive, or the resulting
 *   intrinsics fail [ActiveArrayIntrinsics]'s own validation.
 */
fun activeArrayIntrinsicsFromFocalLength(
    focalLengthMm: Double,
    sensorWidthMm: Double,
    sensorHeightMm: Double,
    pixelArrayWidthPx: Int,
    pixelArrayHeightPx: Int,
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
    require(pixelArrayWidthPx > 0) { "pixelArrayWidthPx must be strictly positive; was $pixelArrayWidthPx" }
    require(pixelArrayHeightPx > 0) { "pixelArrayHeightPx must be strictly positive; was $pixelArrayHeightPx" }
    require(activeArrayWidthPx > 0) { "activeArrayWidthPx must be strictly positive; was $activeArrayWidthPx" }
    require(activeArrayHeightPx > 0) { "activeArrayHeightPx must be strictly positive; was $activeArrayHeightPx" }

    return ActiveArrayIntrinsics(
        fxPx = focalLengthMm / sensorWidthMm * pixelArrayWidthPx,
        fyPx = focalLengthMm / sensorHeightMm * pixelArrayHeightPx,
        cxPx = principalPointXPx ?: (activeArrayWidthPx / 2.0),
        cyPx = principalPointYPx ?: (activeArrayHeightPx / 2.0),
        widthPx = activeArrayWidthPx,
        heightPx = activeArrayHeightPx,
    )
}
