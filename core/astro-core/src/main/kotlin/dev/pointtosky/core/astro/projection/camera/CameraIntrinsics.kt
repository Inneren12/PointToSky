package dev.pointtosky.core.astro.projection.camera

/**
 * Where a [CameraIntrinsics] value came from. Callers (and tests) must be able to tell a real,
 * per-device measurement apart from the AR overlay's legacy fixed-FOV default — this is CAM-1b's
 * central contract: never let a fallback masquerade as calibrated metadata.
 */
enum class CameraIntrinsicsSource {
    /** Derived from Camera2 `CameraCharacteristics` (focal length + physical sensor size). */
    CAMERA_CHARACTERISTICS,

    /** Derived from Camera2 `LENS_INTRINSIC_CALIBRATION` (not parsed as of CAM-1b). */
    CAMERA_INTRINSIC_CALIBRATION,

    /** No usable per-device metadata; mirrors the AR overlay's legacy fixed vertical FOV. */
    LEGACY_FALLBACK,
}

/**
 * A plain, Android-independent camera intrinsics contract.
 *
 * This is a data contract only — CAM-1b introduces it and a resolver in `:mobile` capable of
 * populating it from real device metadata, but the AR renderer still calls the legacy
 * `projectionParams(viewport)` (see [dev.pointtosky.core.astro.projection.projectionParams]).
 * Nothing here is wired into rendering yet.
 *
 * All values are validated eagerly: invalid device metadata must be rejected here, not silently
 * clamped. Both FOVs must be finite and satisfy `0 < fov < 180`. The optional physical dimensions
 * ([focalLengthMm], [sensorWidthMm], [sensorHeightMm]) must be finite and strictly positive when
 * present — a physical dimension can never be zero. The optional principal-point image coordinates
 * ([principalPointXPx], [principalPointYPx]) must be finite and non-negative when present — an
 * image-coordinate axis legitimately starts at pixel `0`, so unlike the physical dimensions they
 * are not required to be strictly positive. Callers that read from potentially-malformed Camera2
 * metadata are expected to catch the resulting [IllegalArgumentException] and fall back to
 * [CameraIntrinsicsSource.LEGACY_FALLBACK] themselves.
 *
 * @property horizontalFovDeg full horizontal field of view, degrees, `0 < fov < 180`.
 * @property verticalFovDeg full vertical field of view, degrees, `0 < fov < 180`.
 * @property focalLengthMm physical focal length in millimetres, when known. Finite and strictly
 *   positive when present — a physical dimension can never be zero.
 * @property sensorWidthMm physical sensor width in millimetres, when known. Finite and strictly
 *   positive when present.
 * @property sensorHeightMm physical sensor height in millimetres, when known. Finite and strictly
 *   positive when present.
 * @property principalPointXPx principal point X in analyzed-image pixels, when known. Finite and
 *   non-negative (not strictly positive) when present — an image-coordinate axis legitimately
 *   starts at pixel `0`, unlike the physical dimensions above. CAM-1b never populates this (see
 *   package docs on `LENS_INTRINSIC_CALIBRATION`); reserved for CAM-1c/1d.
 * @property principalPointYPx principal point Y in analyzed-image pixels, when known. Finite and
 *   non-negative when present.
 * @property source where this value came from; see [CameraIntrinsicsSource].
 */
data class CameraIntrinsics(
    val horizontalFovDeg: Double,
    val verticalFovDeg: Double,
    val focalLengthMm: Double?,
    val sensorWidthMm: Double?,
    val sensorHeightMm: Double?,
    val principalPointXPx: Double?,
    val principalPointYPx: Double?,
    val source: CameraIntrinsicsSource,
) {
    init {
        requireValidFovDeg(horizontalFovDeg, "horizontalFovDeg")
        requireValidFovDeg(verticalFovDeg, "verticalFovDeg")
        requireFiniteAndPositiveIfPresent(focalLengthMm, "focalLengthMm")
        requireFiniteAndPositiveIfPresent(sensorWidthMm, "sensorWidthMm")
        requireFiniteAndPositiveIfPresent(sensorHeightMm, "sensorHeightMm")
        requireFiniteAndNonNegativeIfPresent(principalPointXPx, "principalPointXPx")
        requireFiniteAndNonNegativeIfPresent(principalPointYPx, "principalPointYPx")
    }

    private companion object {
        const val MIN_FOV_DEG = 0.0
        const val MAX_FOV_DEG = 180.0

        fun requireValidFovDeg(valueDeg: Double, name: String) {
            require(valueDeg.isFinite()) { "$name must be finite; was $valueDeg" }
            require(valueDeg > MIN_FOV_DEG && valueDeg < MAX_FOV_DEG) {
                "$name must satisfy 0 < fov < 180; was $valueDeg"
            }
        }

        fun requireFiniteAndPositiveIfPresent(value: Double?, name: String) {
            if (value == null) return
            require(value.isFinite()) { "$name must be finite when present; was $value" }
            require(value > 0.0) { "$name must be positive when present; was $value" }
        }

        fun requireFiniteAndNonNegativeIfPresent(value: Double?, name: String) {
            if (value == null) return
            require(value.isFinite()) { "$name must be finite when present; was $value" }
            require(value >= 0.0) { "$name must be non-negative when present; was $value" }
        }
    }
}

/**
 * Which pixel grid a [CameraIntrinsics] value's [CameraIntrinsics.horizontalFovDeg]/
 * [CameraIntrinsics.verticalFovDeg] are actually measured over (CAM-2a hardening). This distinction
 * exists because CAM-1b's own resolution path (`CameraIntrinsicsResolver.kt`,
 * [CameraIntrinsicsSource.CAMERA_CHARACTERISTICS]) derives FOV from Camera2
 * `SENSOR_INFO_PHYSICAL_SIZE` — the **full physical sensor**'s dimensions — and a selected focal
 * length, with **no** reasoning about `SENSOR_INFO_ACTIVE_ARRAY_SIZE`, `SCALER_CROP_REGION`, or how
 * CameraX scales/crops the active array down into the `ImageAnalysis` output stream. None of that
 * crop/scale metadata is read anywhere in this codebase as of CAM-1b/CAM-2a. A physical-sensor FOV
 * therefore cannot be assumed to equal the analyzed buffer's own FOV — the same physical sensor can
 * back `ImageAnalysis` streams of many different resolutions and aspect ratios, each seeing a
 * different (and currently unrecorded) fraction of that physical FOV.
 *
 * [dev.pointtosky.core.astro.projection.camera.legacyFallbackCameraIntrinsics], by contrast, derives
 * its horizontal FOV from the *analyzed image's own aspect ratio* (`imageWidthPx`/`imageHeightPx`,
 * the exact analysis-buffer dimensions) — it is analysis-buffer-referenced by construction, even
 * though the vertical FOV value itself (56°) is not a calibrated measurement of anything.
 */
enum class CameraIntrinsicsReferenceSpace {
    /**
     * FOV is measured over the same pixel grid [dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata.bufferWidthPx]/
     * `bufferHeightPx` describes — safe to use directly with a buffer-space pinhole model
     * ([dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel]).
     */
    ANALYSIS_BUFFER,

    /**
     * FOV is measured over the physical sensor (or some other non-analysis-buffer region) with no
     * recorded mapping to the analyzed buffer's own pixel grid — **not** safe to feed directly into a
     * buffer-space pinhole model without that missing crop/scale metadata.
     */
    PHYSICAL_SENSOR,
}

/**
 * [CameraIntrinsicsReferenceSpace] this value's FOV was measured over, derived from [CameraIntrinsics.source]
 * — never an independently-settable field, so `source` and `referenceSpace` cannot disagree.
 *
 * - [CameraIntrinsicsSource.LEGACY_FALLBACK] → [CameraIntrinsicsReferenceSpace.ANALYSIS_BUFFER]: always
 *   built from the analyzed image's own dimensions (see [CameraIntrinsicsReferenceSpace]'s KDoc).
 * - [CameraIntrinsicsSource.CAMERA_CHARACTERISTICS] → [CameraIntrinsicsReferenceSpace.PHYSICAL_SENSOR]:
 *   derived from `SENSOR_INFO_PHYSICAL_SIZE`, with no active-array/crop-region reasoning connecting it
 *   to any particular analysis-buffer resolution.
 * - [CameraIntrinsicsSource.CAMERA_INTRINSIC_CALIBRATION] → [CameraIntrinsicsReferenceSpace.PHYSICAL_SENSOR]:
 *   conservative default — this source is unused as of CAM-1b/CAM-2a (`LENS_INTRINSIC_CALIBRATION` is
 *   deliberately unparsed), so nothing has established an analysis-buffer mapping for it either.
 */
val CameraIntrinsics.referenceSpace: CameraIntrinsicsReferenceSpace
    get() =
        when (source) {
            CameraIntrinsicsSource.LEGACY_FALLBACK -> CameraIntrinsicsReferenceSpace.ANALYSIS_BUFFER
            CameraIntrinsicsSource.CAMERA_CHARACTERISTICS -> CameraIntrinsicsReferenceSpace.PHYSICAL_SENSOR
            CameraIntrinsicsSource.CAMERA_INTRINSIC_CALIBRATION -> CameraIntrinsicsReferenceSpace.PHYSICAL_SENSOR
        }
