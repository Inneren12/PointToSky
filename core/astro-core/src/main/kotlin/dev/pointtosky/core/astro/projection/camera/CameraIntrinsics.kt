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
 * Which pixel region a [CameraIntrinsics] value's [CameraIntrinsics.horizontalFovDeg]/
 * [CameraIntrinsics.verticalFovDeg] were actually measured over — carried as **data**, not derived
 * from [CameraIntrinsicsSource] (CAM-2a intrinsics-provenance hardening).
 *
 * ## `source` vs `reference`: two different questions
 * [CameraIntrinsicsSource] answers *"where did these numbers come from?"* (Camera2
 * `CameraCharacteristics`, `LENS_INTRINSIC_CALIBRATION`, or the legacy fixed-FOV fallback).
 * [CameraIntrinsicsReference] answers a completely different question: *"which pixel grid does the
 * stored FOV apply to?"* An earlier revision of this hardening derived the second question's answer
 * purely from the first question's value (`LEGACY_FALLBACK` → always analysis-buffer-safe), which
 * was insufficient: [legacyFallbackCameraIntrinsics] can be — and in tests routinely was —
 * constructed without image dimensions, with dimensions from a different buffer/session, or with a
 * different aspect ratio, then reused against an arbitrary [dev.pointtosky.core.astro.projection.camera.CameraSessionGeometry].
 * Deriving "analysis-buffer-safe" from `source` alone could not detect any of that — it recreated
 * the exact dimensionless/stale-aspect fallback bug CAM-1f's own coordinator was built to prevent,
 * just one layer higher, at the CAM-2a boundary. [CameraIntrinsicsReference] fixes this by making
 * the reference **carry its own dimensions** when it claims to be analysis-buffer-referenced, so a
 * consumer ([dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel.forGeometry])
 * can check them against the buffer it is actually being asked to project into, not just trust a
 * label.
 */
sealed interface CameraIntrinsicsReference {
    /**
     * The FOV is measured over an analysis buffer of exactly [widthPx] × [heightPx] pixels — the
     * *only* variant that can ever be projected with directly, and only when [widthPx]/[heightPx]
     * exactly match the buffer actually being projected into (checked by
     * [dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel.forGeometry] and
     * `dev.pointtosky.core.astro.projection.camera.prediction.projectStars`, not merely assumed here).
     * Matching aspect ratio is **not** sufficient — a `1000x500` reference must not be silently reused
     * for a `2000x1000` buffer, since a wider/taller buffer at the same aspect ratio still changes the
     * pixel-per-degree scale a pinhole model derives.
     */
    data class AnalysisBuffer(
        val widthPx: Int,
        val heightPx: Int,
    ) : CameraIntrinsicsReference {
        init {
            require(widthPx > 0) { "widthPx must be positive; was $widthPx" }
            require(heightPx > 0) { "heightPx must be positive; was $heightPx" }
        }
    }

    /**
     * The FOV is measured over the physical sensor (or another non-buffer region) with no recorded
     * crop/scale mapping to any particular analysis buffer — never safe to feed into a buffer-space
     * pinhole model without that missing metadata. See
     * [dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource.CAMERA_CHARACTERISTICS]'s
     * KDoc for why this codebase cannot derive that mapping today.
     */
    data object PhysicalSensor : CameraIntrinsicsReference

    /**
     * No analysis-buffer dimensions were available when this value was constructed — e.g.
     * [legacyFallbackCameraIntrinsics] called before the first analyzed frame's real dimensions were
     * known. Distinct from [AnalysisBuffer]: this reference carries **no** dimensions to check
     * against anything, so it must never be treated as analysis-buffer-compatible. Preserves the
     * legacy fallback's ability to be constructed dimensionlessly (for callers outside CAM-2a's
     * buffer-projection path) while keeping that uncertainty explicit and machine-checkable rather
     * than silently defaulting to a guessed aspect ratio.
     */
    data object Unspecified : CameraIntrinsicsReference
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
 * [reference] and [source] are independent fields describing different things (see
 * [CameraIntrinsicsReference]'s KDoc) but are not independently *free*: `init` below re-validates
 * the one cross-field consistency rule production code must uphold (defense in depth, matching this
 * codebase's convention of checking even provably-true invariants) — [CameraIntrinsicsSource.CAMERA_CHARACTERISTICS]/
 * [CameraIntrinsicsSource.CAMERA_INTRINSIC_CALIBRATION] must carry [CameraIntrinsicsReference.PhysicalSensor],
 * and [CameraIntrinsicsSource.LEGACY_FALLBACK] must carry [CameraIntrinsicsReference.AnalysisBuffer] or
 * [CameraIntrinsicsReference.Unspecified]. This does **not** mean [reference] is derived from
 * [source] — an [CameraIntrinsicsReference.AnalysisBuffer]'s [CameraIntrinsicsReference.AnalysisBuffer.widthPx]/
 * `heightPx` are still caller-supplied data with no relationship to `source` at all; the rule above
 * only rules out the specific mislabeling this hardening exists to prevent (a physical-sensor
 * measurement claiming to be analysis-buffer-safe).
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
 * @property source where this value's numbers came from; see [CameraIntrinsicsSource].
 * @property reference which pixel region the FOV was measured over; see [CameraIntrinsicsReference].
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
    val reference: CameraIntrinsicsReference,
) {
    init {
        requireValidFovDeg(horizontalFovDeg, "horizontalFovDeg")
        requireValidFovDeg(verticalFovDeg, "verticalFovDeg")
        requireFiniteAndPositiveIfPresent(focalLengthMm, "focalLengthMm")
        requireFiniteAndPositiveIfPresent(sensorWidthMm, "sensorWidthMm")
        requireFiniteAndPositiveIfPresent(sensorHeightMm, "sensorHeightMm")
        requireFiniteAndNonNegativeIfPresent(principalPointXPx, "principalPointXPx")
        requireFiniteAndNonNegativeIfPresent(principalPointYPx, "principalPointYPx")
        when (source) {
            CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, CameraIntrinsicsSource.CAMERA_INTRINSIC_CALIBRATION ->
                require(reference is CameraIntrinsicsReference.PhysicalSensor) {
                    "source=$source must carry reference=PhysicalSensor (no analysis-buffer mapping is " +
                        "known for it); was $reference"
                }
            CameraIntrinsicsSource.LEGACY_FALLBACK ->
                require(
                    reference is CameraIntrinsicsReference.AnalysisBuffer ||
                        reference is CameraIntrinsicsReference.Unspecified,
                ) {
                    "source=LEGACY_FALLBACK must carry reference=AnalysisBuffer or Unspecified; was $reference"
                }
        }
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
