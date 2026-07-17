package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.ActiveArrayIntrinsics
import dev.pointtosky.core.astro.projection.camera.ActiveArrayRect
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.MatrixIntrinsicsMappingResult
import dev.pointtosky.core.astro.projection.camera.SensorToBufferDomainConsistency
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import dev.pointtosky.core.astro.projection.camera.activeArrayIntrinsicsFromFocalLength
import dev.pointtosky.core.astro.projection.camera.mapActiveArrayIntrinsicsThroughMatrix
import dev.pointtosky.core.astro.projection.camera.toActiveArrayLocalRect
import dev.pointtosky.core.astro.projection.camera.toCameraIntrinsics
import kotlin.math.abs

/**
 * Typed outcome of [resolveAnalysisBufferIntrinsics] (CAM-2c §6, fix §1) — an explicit result for
 * every way the calibrated Camera2-to-analysis-buffer mapping can succeed or fail, so a caller (and a
 * test) can tell exactly *why* the calibrated path was unavailable rather than being handed a single
 * collapsed `null`/fallback. No silent fallback: every path returns one of these variants, never a
 * guess.
 */
sealed interface AnalysisBufferIntrinsicsResolution {
    /**
     * A real, calibrated mapping succeeded. [intrinsics] always carries
     * `source = `[CameraIntrinsicsSource.CAMERA_CHARACTERISTICS]`, `reference` is an
     * [CameraIntrinsicsReference.AnalysisBuffer] sized to the exact buffer this resolution was
     * requested for, and `quality` is non-`null` — see [CameraIntrinsics]'s own cross-field `init`
     * rule, re-checked here as defense in depth. [diagnostics] carries every intermediate quantity
     * for the internal debug panel (CAM-2c §9) — never consumed by projection itself.
     */
    data class Resolved(
        val intrinsics: CameraIntrinsics,
        val diagnostics: CameraCalibrationDiagnostics,
    ) : AnalysisBufferIntrinsicsResolution {
        init {
            require(intrinsics.source == CameraIntrinsicsSource.CAMERA_CHARACTERISTICS) {
                "Resolved intrinsics must carry source=CAMERA_CHARACTERISTICS; was ${intrinsics.source}"
            }
            require(intrinsics.reference is CameraIntrinsicsReference.AnalysisBuffer) {
                "Resolved intrinsics must carry an AnalysisBuffer reference; was ${intrinsics.reference}"
            }
            require(intrinsics.quality != null) { "Resolved intrinsics must carry a non-null quality" }
        }
    }

    /** `SENSOR_INFO_ACTIVE_ARRAY_SIZE` is missing, or its width/height is not strictly positive. */
    data object MissingActiveArray : AnalysisBufferIntrinsicsResolution

    /** `SENSOR_INFO_PHYSICAL_SIZE` is missing, or its width/height is not finite and strictly positive. */
    data object MissingPhysicalSensorSize : AnalysisBufferIntrinsicsResolution

    /**
     * The focal-length-derived fallback was required (no usable `LENS_INTRINSIC_CALIBRATION`), but
     * `SENSOR_INFO_PIXEL_ARRAY_SIZE` is missing or its width/height is not strictly positive (CAM-2c
     * fix §P2). Pixel pitch (`fx`/`fy` in pixels) is derived from the *full pixel array*, never the
     * active array (see `dev.pointtosky.core.astro.projection.camera.activeArrayIntrinsicsFromFocalLength`'s
     * KDoc) — so this fallback cannot proceed without it, and this codebase never silently substitutes
     * `SENSOR_INFO_ACTIVE_ARRAY_SIZE` instead (that substitution is exactly the defect this fix
     * corrects). Distinct from [MissingActiveArray]: the active array itself may be perfectly valid
     * here, only the pixel array is unavailable. **Never** returned when a usable calibrated `K` is
     * found first — `LENS_INTRINSIC_CALIBRATION` already supplies `fx`/`fy` directly in pixels, so the
     * calibrated path needs no pixel-array size at all (see [resolveAnalysisBufferIntrinsics]'s KDoc,
     * resolution-order step 5).
     */
    data object MissingPixelArraySize : AnalysisBufferIntrinsicsResolution

    /**
     * `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` has no exactly-one usable candidate — missing, empty, all
     * invalid, or ambiguous (more than one valid value; see [selectFocalLengthMm]'s own KDoc for why
     * an ambiguous array is never resolved by guessing).
     */
    data object MissingFocalLength : AnalysisBufferIntrinsicsResolution

    /**
     * No per-frame sensor-to-buffer matrix was available at all — the caller had none to offer (e.g.
     * resolution requested before any frame was analyzed, or `ImageInfo.getSensorToBufferTransformMatrix()`
     * itself reported a non-finite value; see [sensorToBufferTransformFromMatrixValues]).
     */
    data object MissingSensorToBufferTransform : AnalysisBufferIntrinsicsResolution

    /**
     * A sensor-to-buffer matrix was present but [transformClass] — a
     * [dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass] this codebase has no
     * documented, tested formula for composing into a valid pinhole model (CAM-2c fix §1) — is one of
     * `MIRRORED`/`GENERAL_AFFINE_UNSUPPORTED`/`PROJECTIVE_UNSUPPORTED`/`SINGULAR`. Distinct from
     * [MissingSensorToBufferTransform]: here a real matrix *was* reported, it is just not one this
     * codebase can safely use — never silently approximated as axis-aligned.
     */
    data class UnsupportedSensorToBufferTransform(
        val transformClass: SensorToBufferTransformClass,
    ) : AnalysisBufferIntrinsicsResolution

    /**
     * The sensor-to-buffer matrix classified as [transformClass] — one of
     * [SensorToBufferTransformClass.ORTHOGONAL_90]/`ORTHOGONAL_180`/`ORTHOGONAL_270` — which
     * [dev.pointtosky.core.astro.projection.camera.mapActiveArrayIntrinsicsThroughMatrix] is
     * algebraically willing to compose into a pinhole model (CAM-2c fix §1/§2), but which this
     * resolver refuses to use anyway (CAM-2c fix round 2 §4): neither the public CameraX contract
     * (`ImageInfo.getSensorToBufferTransformMatrix()`) nor Camera2's own documentation states how a
     * rotated/permuted sensor-to-buffer matrix should combine with a given
     * `CameraFrameMetadata.rotationDegrees` value for a real device — that combination has never been
     * observed or documented, only reasoned about algebraically. Distinct from
     * [UnsupportedSensorToBufferTransform]: here the *math* is available and tested
     * (`AnalysisBufferIntrinsicsMappingTest`); what is missing is *proof* that using it is physically
     * correct, not a formula. Shipping an elegant-but-unproven composition would trade safety for
     * appearance of completeness — this codebase chooses safety. Falls through to the unchanged CAM-1b
     * path exactly like any other non-`Resolved` outcome (see [resolveCameraIntrinsicsPreferringCalibration]).
     * In real CameraX usage this is expected to be unreachable in practice, since CameraX's own
     * reference implementation (`CameraUseCaseAdapter.calculateSensorToBufferTransformMatrix`) is
     * provably `AXIS_ALIGNED_0`-only by construction — this guard exists for whichever OEM HAL or
     * CameraX version this codebase has not traced, not because it is known to occur.
     */
    data class RotationOwnershipUnproven(
        val transformClass: SensorToBufferTransformClass,
    ) : AnalysisBufferIntrinsicsResolution

    /**
     * **Reserved, not currently returned by [resolveAnalysisBufferIntrinsics] (CAM-2c domain-consistency
     * fix).** [transformClass] classified as a structurally supported class (would otherwise compose a
     * `K'`), but
     * `dev.pointtosky.core.astro.projection.camera.assessSensorToBufferDomainConsistency`'s own,
     * deliberately strict, whole-source-domain check found [consistency] to be something other than
     * [SensorToBufferDomainConsistency.CONSISTENT] — see that function's own KDoc, "Scope and known
     * limitation," for exactly why a `MAPPED_BOUNDS_MISMATCH` verdict is common and *expected* for an
     * ordinary, legitimate active-array crop (most real devices), not just for a broken transform like
     * the real Pixel 9 identity-matrix evidence (`docs/validation/cam_2c_pixel9_evidence.md`) that
     * motivated adding this check at all.
     *
     * [resolveAnalysisBufferIntrinsics] computes this same assessment as diagnostic evidence (see
     * [AnalysisBufferIntrinsicsResolution.Resolved]'s own attached
     * `dev.pointtosky.mobile.ar.camera.CameraCalibrationDiagnostics` — wired separately, at the
     * `internalDebug` diagnostics-export boundary, not on this sealed type) but does **not** return this
     * variant instead of [AnalysisBufferIntrinsicsResolution.Resolved] today: doing so would silently
     * flip a large share of already-working, non-logical-multi-camera devices (any device whose
     * sensor-native aspect ratio does not exactly equal its analysis buffer's — the common case) from a
     * resolved calibration to a rejection, which is both a real behavior change this fix's own scope
     * explicitly rules out ("do not change outcome precedence merely to expose the new consistency
     * failure") and not justified by evidence this codebase actually has — this check is proven strict
     * enough to catch the identity-matrix defect, not proven complete enough to certify every legitimate
     * crop. This variant exists so a future pass — once CAM-2d or an equivalent supplies real proof of
     * CameraX's true cropped-domain contract — has a typed, already-integrated place to land that
     * decision (mirroring [RotationOwnershipUnproven]'s own "the math exists, the proof does not" shape)
     * rather than inventing new plumbing under time pressure.
     */
    data class DomainConsistencyUnproven(
        val transformClass: SensorToBufferTransformClass,
        val consistency: SensorToBufferDomainConsistency,
    ) : AnalysisBufferIntrinsicsResolution

    /**
     * This camera's `REQUEST_AVAILABLE_CAPABILITIES` includes `LOGICAL_MULTI_CAMERA` (CAM-2c §1): its
     * static `CameraCharacteristics` cannot be trusted to describe whichever physical sensor actually
     * produced a given analyzed frame, so no calibrated mapping is attempted at all rather than risk
     * silently mixing metadata from a different physical camera.
     *
     * This codebase's pinned `androidx.camera:camera-camera2:1.3.4` cannot resolve this any more
     * precisely (CAM-2c fix §5, "Option C"): neither binding a concrete physical camera
     * (`CameraSelector.setPhysicalCameraId` — only available from `1.4.0-beta01`) nor reading
     * per-frame physical-camera provenance (`CameraInfo.getPhysicalCameraInfos()` — same version
     * floor) is possible at this dependency version. [physicalCameraIdsForDiagnostics] surfaces
     * whatever this device's logical camera declares (when available) purely for the diagnostics
     * panel; it does not change this guard's outcome. A future CameraX version bump could revisit
     * Option A/B; until then, CAM-2c is **blocked** on any logical-multi-camera device, which very
     * plausibly includes the Pixel 9's own rear camera (real Pixel devices have used logical
     * multi-camera rear configurations since the Pixel 4).
     */
    data class UnsupportedLogicalMultiCameraMapping(
        val cameraId: String?,
        val physicalCameraIdsForDiagnostics: Set<String>?,
    ) : AnalysisBufferIntrinsicsResolution

    /**
     * Metadata was present but failed validation somewhere in the mapping — e.g. `LENS_INTRINSIC_CALIBRATION`
     * with the wrong array size, a crop region that does not lie within the active array, or a
     * computed [CameraIntrinsics] that fails its own eager validation. [reason] is a short,
     * non-device-specific diagnostic code, never a raw exception message.
     */
    data class InvalidMetadata(val reason: String) : AnalysisBufferIntrinsicsResolution
}

/** Diagnostic reason codes [resolveAnalysisBufferIntrinsics] uses for [AnalysisBufferIntrinsicsResolution.InvalidMetadata]. */
internal object AnalysisBufferIntrinsicsInvalidMetadataReason {
    const val CHARACTERISTICS_UNAVAILABLE = "camera_characteristics_unavailable"
    const val ACTIVE_ARRAY_INTRINSICS_INVALID = "active_array_intrinsics_invalid"
    const val CROP_REGION_INVALID = "crop_region_invalid"
    const val BUFFER_INTRINSICS_INVALID = "buffer_intrinsics_invalid"
}

/**
 * Non-failure diagnostic reason codes [resolveAnalysisBufferIntrinsics] attaches to a successful
 * [AnalysisBufferIntrinsicsResolution.Resolved] whose [CameraCalibrationDiagnostics.skewDiagnosticReason]
 * is non-`null` (CAM-2c fix §3) — distinct from [AnalysisBufferIntrinsicsInvalidMetadataReason], since
 * these never prevent resolution, they only explain why [CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT]
 * was used instead of [CameraIntrinsicsQuality.CALIBRATED] despite `LENS_INTRINSIC_CALIBRATION` being
 * otherwise structurally usable.
 */
internal object CameraCalibrationDiagnosticReason {
    const val NON_ZERO_INTRINSIC_SKEW = "non_zero_intrinsic_skew"
}

/**
 * Tolerance (CAM-2c fix §3), in pixels, for treating `LENS_INTRINSIC_CALIBRATION`'s fifth element
 * (`skew`) as negligible measurement/rounding noise rather than a genuine non-rectilinear lens
 * characteristic. A real pinhole/rectilinear lens has **zero** skew; Camera2's own factory
 * calibration on such a lens is expected to report either exactly `0` or a tiny float artifact of the
 * calibration procedure — this codebase treats anything within half a pixel of zero as that kind of
 * noise. A larger value means the lens genuinely couples horizontal pixel position to vertical ray
 * angle in a way this codebase's skew-free downstream pinhole model
 * ([dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel]) cannot represent
 * — silently dropping it would make the calibrated principal point *look* more precise than it
 * actually is for off-axis stars, so [resolveAnalysisBufferIntrinsics] rejects the calibrated `K`
 * entirely in that case (falling back to [activeArrayIntrinsicsFromFocalLength], which has no skew
 * term to begin with) rather than silently using a slightly-wrong one.
 */
internal const val INTRINSIC_SKEW_TOLERANCE_PX = 0.5

/**
 * `LENS_INTRINSIC_CALIBRATION` is `[fx, fy, cx, cy, skew]`, five floats, with `fx`/`fy` strictly
 * positive when meaningful. Coordinate-space verification (whether it is safe to read directly as
 * active-array pixels) is a separate check — see [preCorrectionActiveArrayMatchesActiveArray]. Skew
 * tolerance is a separate check too — see [isIntrinsicSkewWithinTolerance] and
 * [INTRINSIC_SKEW_TOLERANCE_PX] — deliberately not folded in here, so a caller can distinguish
 * "structurally malformed" from "structurally fine, but skew is too large to trust".
 */
private fun isUsableLensIntrinsicCalibration(calibration: FloatArray?): Boolean =
    calibration != null &&
        calibration.size == 5 &&
        calibration.all { it.isFinite() } &&
        calibration[0] > 0f &&
        calibration[1] > 0f

/**
 * `true` when [calibration]'s skew term (index `4`) is within [INTRINSIC_SKEW_TOLERANCE_PX] of zero
 * (CAM-2c fix §3). Only ever called after [isUsableLensIntrinsicCalibration] has already confirmed
 * `calibration` has exactly 5 finite elements.
 */
private fun isIntrinsicSkewWithinTolerance(calibration: FloatArray): Boolean =
    abs(calibration[4]) <= INTRINSIC_SKEW_TOLERANCE_PX

/**
 * `LENS_INTRINSIC_CALIBRATION`'s official contract places it in `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`
 * coordinates, **not** necessarily `SENSOR_INFO_ACTIVE_ARRAY_SIZE` — a distinct key some devices
 * report when geometric distortion correction shifts/crops the array. Per that same Android
 * contract, when a device does **not** report `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE` at all,
 * the active array *is* the pre-correction array (no correction applied), so the two coordinate
 * spaces are trivially identical and the calibration is safe to use directly. When the device
 * *does* report one, it must be checked for an exact match on **all four edges** — never just
 * width/height (CAM-2c fix §6: a matching size with a shifted origin is not the same rectangle) —
 * before [isUsableLensIntrinsicCalibration]'s numbers are read as active-array pixels.
 */
private fun preCorrectionActiveArrayMatchesActiveArray(snapshot: CameraCharacteristicsSnapshot): Boolean {
    val preLeft = snapshot.preCorrectionActiveArrayLeftPx
    val preTop = snapshot.preCorrectionActiveArrayTopPx
    val preRight = snapshot.preCorrectionActiveArrayRightPx
    val preBottom = snapshot.preCorrectionActiveArrayBottomPx
    if (preLeft == null && preTop == null && preRight == null && preBottom == null) return true
    if (preLeft == null || preTop == null || preRight == null || preBottom == null) return false
    return preLeft == snapshot.activeArrayLeftPx &&
        preTop == snapshot.activeArrayTopPx &&
        preRight == snapshot.activeArrayRightPx &&
        preBottom == snapshot.activeArrayBottomPx
}

/**
 * Resolves calibrated pinhole intrinsics over one exact `ImageAnalysis` buffer from real Camera2
 * `CameraCharacteristics`, mapped through the exact CameraX sensor-to-buffer transform for that
 * buffer (CAM-2c, fix §1-§6). Never silently falls back — every failure mode is one of
 * [AnalysisBufferIntrinsicsResolution]'s explicit variants.
 *
 * Two overloads exist (CAM-2c runtime integration fix P1): this [CameraCharacteristicsSource]-based
 * one reads the source exactly once (via [readSnapshotOrNull]) and delegates to the
 * [CameraCharacteristicsSnapshot]-based core overload below — callers that also need the *same*
 * snapshot for a CAM-1b fallback decision or diagnostics (`resolveCameraIntrinsicsPreferringCalibration`)
 * should call the snapshot-based overload directly with their own already-read snapshot instead of
 * calling this one, so the source is never read more than once per session resolution.
 *
 * Resolution order:
 *  1. Read [source]'s snapshot (any thrown exception → [AnalysisBufferIntrinsicsResolution.InvalidMetadata]).
 *  2. Reject a logical multi-camera outright ([AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping]) —
 *     see that variant's KDoc for why this codebase cannot do better at its pinned CameraX version.
 *  3. Require `SENSOR_INFO_ACTIVE_ARRAY_SIZE` ([AnalysisBufferIntrinsicsResolution.MissingActiveArray]),
 *     `SENSOR_INFO_PHYSICAL_SIZE` ([AnalysisBufferIntrinsicsResolution.MissingPhysicalSensorSize]),
 *     and exactly one valid `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` candidate
 *     ([AnalysisBufferIntrinsicsResolution.MissingFocalLength] — reusing [selectFocalLengthMm]'s
 *     same ambiguity rule as CAM-1b).
 *  4. Require [sensorToBufferTransform] ([AnalysisBufferIntrinsicsResolution.MissingSensorToBufferTransform]).
 *  5. Build active-array intrinsics: `LENS_INTRINSIC_CALIBRATION` when [isUsableLensIntrinsicCalibration],
 *     [preCorrectionActiveArrayMatchesActiveArray], and [isIntrinsicSkewWithinTolerance] all hold
 *     (`quality = `[CameraIntrinsicsQuality.CALIBRATED]) — **needing no `SENSOR_INFO_PIXEL_ARRAY_SIZE`
 *     at all**, since `fx`/`fy` are already supplied directly in pixels — else
 *     [activeArrayIntrinsicsFromFocalLength], which **does** require `SENSOR_INFO_PIXEL_ARRAY_SIZE`
 *     (`quality = `[CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT] when present —
 *     [AnalysisBufferIntrinsicsResolution.MissingPixelArraySize] when it is missing/invalid, CAM-2c fix
 *     §P2 — with [CameraCalibrationDiagnosticReason.NON_ZERO_INTRINSIC_SKEW] recorded when the *only*
 *     reason calibration was rejected is the skew tolerance, so the diagnostics panel can distinguish
 *     that from "no calibration reported at all"). **The resulting principal point is read/derived
 *     directly as active-array-local** (CAM-2c fix round 3, §P1) — `LENS_INTRINSIC_CALIBRATION`'s
 *     `cx`/`cy` are already local to the pre-correction active-array rectangle's own top-left per
 *     Android's own documented contract, and the focal-length-derived default is the active array's
 *     own local centre; neither is translated by `SENSOR_INFO_ACTIVE_ARRAY_SIZE.left`/`.top` (a prior
 *     revision of this fix did add that translation — a regression, since it converted an
 *     already-local value into an incorrect hybrid; see [ActiveArrayIntrinsics]'s KDoc for the
 *     corrected contract and its source evidence).
 *  6. Map through [sensorToBufferTransform] via [mapActiveArrayIntrinsicsThroughMatrix] — full 3x3
 *     matrix composition, not an independent per-field formula (CAM-2c fix §1/§2). A
 *     [MatrixIntrinsicsMappingResult.Unsupported] outcome here returns
 *     [AnalysisBufferIntrinsicsResolution.UnsupportedSensorToBufferTransform]; a
 *     [MatrixIntrinsicsMappingResult.Mapped] whose `transformClass` is anything other than
 *     [SensorToBufferTransformClass.AXIS_ALIGNED_0] returns
 *     [AnalysisBufferIntrinsicsResolution.RotationOwnershipUnproven] instead of using it (CAM-2c fix
 *     round 2 §4 — see that variant's KDoc).
 *  7. Validate the matrix-inferred crop region ([toActiveArrayLocalRect], active-array-local) lies
 *     within `[0, activeArrayWidthPx] x [0, activeArrayHeightPx]` (CAM-2c fix round 3, §P1) — never
 *     against the full-pixel-array-relative [ActiveArrayRect] edges, which is only the same range when
 *     the active array happens to start at the full pixel array's own origin.
 *  8. Convert to [CameraIntrinsics] ([toCameraIntrinsics]) with
 *     `source = `[CameraIntrinsicsSource.CAMERA_CHARACTERISTICS]`, `reference =
 *     AnalysisBuffer(`[bufferWidthPx]`, `[bufferHeightPx]`)`.
 *
 * Any [IllegalArgumentException] thrown by the pure `:core:astro-core` math in steps 5-8 is caught
 * and reported as [AnalysisBufferIntrinsicsResolution.InvalidMetadata] — defensive, since the checks
 * above should already rule out the inputs that would cause one.
 */
internal fun resolveAnalysisBufferIntrinsics(
    source: CameraCharacteristicsSource,
    sensorToBufferTransform: SensorToBufferMatrix3?,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
): AnalysisBufferIntrinsicsResolution =
    resolveAnalysisBufferIntrinsics(source.readSnapshotOrNull(), sensorToBufferTransform, bufferWidthPx, bufferHeightPx)

/**
 * The snapshot-based core [resolveAnalysisBufferIntrinsics] delegates to (CAM-2c runtime integration
 * fix P1) — takes an already-read [CameraCharacteristicsSnapshot] directly rather than reading a
 * [CameraCharacteristicsSource] itself, so a caller that also needs the same snapshot for a CAM-1b
 * fallback decision or for runtime diagnostics (`resolveCameraIntrinsicsPreferringCalibration`) reads
 * the source exactly once and passes the one resulting immutable value to every consumer, rather than
 * each consumer re-reading (and each read is not guaranteed to observe the same values). `snapshot ==
 * null` — the source-based overload's read failed, or a caller has no source to read at all — is
 * reported as [AnalysisBufferIntrinsicsResolution.InvalidMetadata] with
 * [AnalysisBufferIntrinsicsInvalidMetadataReason.CHARACTERISTICS_UNAVAILABLE], exactly matching the
 * source-based overload's own historical behavior for a thrown exception.
 */
internal fun resolveAnalysisBufferIntrinsics(
    snapshot: CameraCharacteristicsSnapshot?,
    sensorToBufferTransform: SensorToBufferMatrix3?,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
): AnalysisBufferIntrinsicsResolution {
    if (snapshot == null) {
        return AnalysisBufferIntrinsicsResolution.InvalidMetadata(AnalysisBufferIntrinsicsInvalidMetadataReason.CHARACTERISTICS_UNAVAILABLE)
    }

    if (snapshot.isLogicalMultiCamera) {
        return AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping(
            cameraId = snapshot.cameraId,
            physicalCameraIdsForDiagnostics = snapshot.physicalCameraIds,
        )
    }

    val activeLeft = snapshot.activeArrayLeftPx
    val activeTop = snapshot.activeArrayTopPx
    val activeRight = snapshot.activeArrayRightPx
    val activeBottom = snapshot.activeArrayBottomPx
    if (activeLeft == null || activeTop == null || activeRight == null || activeBottom == null) {
        return AnalysisBufferIntrinsicsResolution.MissingActiveArray
    }
    val activeArrayWidthPx = activeRight - activeLeft
    val activeArrayHeightPx = activeBottom - activeTop
    if (activeArrayWidthPx <= 0 || activeArrayHeightPx <= 0) {
        return AnalysisBufferIntrinsicsResolution.MissingActiveArray
    }
    // The actual, ground-truth SENSOR_INFO_ACTIVE_ARRAY_SIZE rectangle, relative to the full pixel
    // array (CAM-2c fix round 2 §1/§2; corrected round 3 §P1) - never assumed to start at (0,0).
    // Diagnostics/provenance only: never folded into active-array-local principal-point math or the
    // crop-bounds check below (see ActiveArrayRect's own KDoc for why).
    val activeArrayRect =
        ActiveArrayRect(
            leftPx = activeLeft.toDouble(),
            topPx = activeTop.toDouble(),
            rightPx = activeRight.toDouble(),
            bottomPx = activeBottom.toDouble(),
        )

    val sensorWidthMm = snapshot.sensorPhysicalWidthMm
    val sensorHeightMm = snapshot.sensorPhysicalHeightMm
    if (sensorWidthMm == null || sensorHeightMm == null ||
        !sensorWidthMm.isFinite() || !sensorHeightMm.isFinite() ||
        sensorWidthMm <= 0f || sensorHeightMm <= 0f
    ) {
        return AnalysisBufferIntrinsicsResolution.MissingPhysicalSensorSize
    }

    val focalLengthMm =
        when (val selection = selectFocalLengthMm(snapshot.availableFocalLengthsMm)) {
            is FocalLengthSelection.Resolved -> selection.focalLengthMm
            FocalLengthSelection.NoneValid, FocalLengthSelection.Ambiguous -> return AnalysisBufferIntrinsicsResolution.MissingFocalLength
        }

    if (sensorToBufferTransform == null) {
        return AnalysisBufferIntrinsicsResolution.MissingSensorToBufferTransform
    }

    val calibration = snapshot.lensIntrinsicCalibration
    val calibrationStructurallyUsable =
        isUsableLensIntrinsicCalibration(calibration) && preCorrectionActiveArrayMatchesActiveArray(snapshot)
    // isIntrinsicSkewWithinTolerance indexes calibration[4] unconditionally - only ever safe to call
    // once calibrationStructurallyUsable has already confirmed calibration has exactly 5 elements.
    val calibrationSkewWithinTolerance = calibrationStructurallyUsable && isIntrinsicSkewWithinTolerance(checkNotNull(calibration))
    val useLensIntrinsicCalibration = calibrationStructurallyUsable && calibrationSkewWithinTolerance
    val skewDiagnosticReason =
        if (calibrationStructurallyUsable && !calibrationSkewWithinTolerance) {
            CameraCalibrationDiagnosticReason.NON_ZERO_INTRINSIC_SKEW
        } else {
            null
        }

    val active: ActiveArrayIntrinsics
    val quality: CameraIntrinsicsQuality
    try {
        if (useLensIntrinsicCalibration) {
            checkNotNull(calibration)
            // LENS_INTRINSIC_CALIBRATION's cx/cy are already active-array-local (CAM-2c fix round 3,
            // §P1 - see ActiveArrayIntrinsics's KDoc for the AOSP-sourced contract): read directly,
            // never translated by activeLeft/activeTop. preCorrectionActiveArrayMatchesActiveArray
            // has already confirmed the pre-correction rectangle exactly matches the active array
            // (all four edges) whenever calibration is used, so the two rectangles' local coordinate
            // systems coincide and no separate pre-correction-specific handling is needed.
            active =
                ActiveArrayIntrinsics(
                    fxPx = calibration[0].toDouble(),
                    fyPx = calibration[1].toDouble(),
                    cxPx = calibration[2].toDouble(),
                    cyPx = calibration[3].toDouble(),
                    widthPx = activeArrayWidthPx,
                    heightPx = activeArrayHeightPx,
                    skewPx = calibration[4].toDouble(),
                )
            quality = CameraIntrinsicsQuality.CALIBRATED
        } else {
            // The focal-derived fallback (unlike a usable calibrated K) needs SENSOR_INFO_PIXEL_ARRAY_SIZE
            // to derive fx/fy (CAM-2c fix §P2) - pixel pitch is physicalSizeMm / pixelArrayPx, not
            // physicalSizeMm / activeArrayPx (see activeArrayIntrinsicsFromFocalLength's KDoc for why).
            // Never silently substitute activeArrayWidthPx/HeightPx here.
            val pixelArrayWidthPx = snapshot.pixelArrayWidthPx
            val pixelArrayHeightPx = snapshot.pixelArrayHeightPx
            if (pixelArrayWidthPx == null || pixelArrayHeightPx == null || pixelArrayWidthPx <= 0 || pixelArrayHeightPx <= 0) {
                return AnalysisBufferIntrinsicsResolution.MissingPixelArraySize
            }
            active =
                activeArrayIntrinsicsFromFocalLength(
                    focalLengthMm = focalLengthMm,
                    sensorWidthMm = sensorWidthMm.toDouble(),
                    sensorHeightMm = sensorHeightMm.toDouble(),
                    pixelArrayWidthPx = pixelArrayWidthPx,
                    pixelArrayHeightPx = pixelArrayHeightPx,
                    activeArrayWidthPx = activeArrayWidthPx,
                    activeArrayHeightPx = activeArrayHeightPx,
                )
            quality = CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT
        }
    } catch (_: IllegalArgumentException) {
        return AnalysisBufferIntrinsicsResolution.InvalidMetadata(AnalysisBufferIntrinsicsInvalidMetadataReason.ACTIVE_ARRAY_INTRINSICS_INVALID)
    }

    val mapping =
        try {
            mapActiveArrayIntrinsicsThroughMatrix(active, sensorToBufferTransform, bufferWidthPx, bufferHeightPx)
        } catch (_: IllegalArgumentException) {
            return AnalysisBufferIntrinsicsResolution.InvalidMetadata(AnalysisBufferIntrinsicsInvalidMetadataReason.CROP_REGION_INVALID)
        }
    val bufferValues =
        when (mapping) {
            is MatrixIntrinsicsMappingResult.Unsupported ->
                return AnalysisBufferIntrinsicsResolution.UnsupportedSensorToBufferTransform(mapping.transformClass)
            is MatrixIntrinsicsMappingResult.Mapped ->
                // CAM-2c fix round 2 §4: only AXIS_ALIGNED_0 is proven correct in combination with
                // CameraFrameMetadata.rotationDegrees - the core math can compose ORTHOGONAL_90/180/270
                // too (see mapActiveArrayIntrinsicsThroughMatrix's own KDoc), but this resolver, which
                // makes a real production/device claim, refuses that unproven combination.
                if (mapping.transformClass != SensorToBufferTransformClass.AXIS_ALIGNED_0) {
                    return AnalysisBufferIntrinsicsResolution.RotationOwnershipUnproven(mapping.transformClass)
                } else {
                    mapping.values
                }
        }
    val cropRegion =
        try {
            sensorToBufferTransform.toActiveArrayLocalRect(bufferWidthPx, bufferHeightPx)
        } catch (_: IllegalArgumentException) {
            return AnalysisBufferIntrinsicsResolution.InvalidMetadata(AnalysisBufferIntrinsicsInvalidMetadataReason.CROP_REGION_INVALID)
        }
    // Tolerance absorbs float round-trip noise from inverting the matrix (e.g. a buffer scale
    // computed as bufferWidthPx/activeArrayWidthPx, then inverted back) - never a physically
    // meaningful margin. Pixel counts here are always at least tens of pixels, so 1e-6px is many
    // orders of magnitude below any real crop discrepancy.
    //
    // CAM-2c fix round 3 §P1: cropRegion is active-array-local (the same space the matrix itself
    // consumes - see ActiveArrayLocalRect's KDoc), so it is validated against [0, activeArrayWidthPx]
    // x [0, activeArrayHeightPx] - never against activeArrayRect's full-pixel-array-relative edges,
    // which is a different coordinate space entirely except when the active array happens to start
    // at the full pixel array's own origin.
    val cropBoundsTolerancePx = 1e-6
    if (cropRegion.leftPx < 0.0 - cropBoundsTolerancePx ||
        cropRegion.topPx < 0.0 - cropBoundsTolerancePx ||
        cropRegion.rightPx > activeArrayWidthPx + cropBoundsTolerancePx ||
        cropRegion.bottomPx > activeArrayHeightPx + cropBoundsTolerancePx
    ) {
        // The matrix is mathematically valid but physically inconsistent with the reported active
        // array: it implies reading sensor pixels outside SENSOR_INFO_ACTIVE_ARRAY_SIZE entirely.
        return AnalysisBufferIntrinsicsResolution.InvalidMetadata(AnalysisBufferIntrinsicsInvalidMetadataReason.CROP_REGION_INVALID)
    }

    return try {
        val intrinsics =
            bufferValues.toCameraIntrinsics(
                focalLengthMm = focalLengthMm,
                sensorWidthMm = sensorWidthMm.toDouble(),
                sensorHeightMm = sensorHeightMm.toDouble(),
                quality = quality,
            )
        val diagnostics =
            CameraCalibrationDiagnostics.of(
                active = active,
                activeArrayRect = activeArrayRect,
                cropRegion = cropRegion,
                bufferValues = bufferValues,
                sensorWidthMm = sensorWidthMm.toDouble(),
                sensorHeightMm = sensorHeightMm.toDouble(),
                focalLengthMm = focalLengthMm,
                quality = quality,
                transformClass = mapping.transformClass,
                skewDiagnosticReason = skewDiagnosticReason,
                cameraId = snapshot.cameraId,
                isLogicalMultiCamera = snapshot.isLogicalMultiCamera,
                physicalCameraIds = snapshot.physicalCameraIds,
                pixelArrayWidthPx = snapshot.pixelArrayWidthPx,
                pixelArrayHeightPx = snapshot.pixelArrayHeightPx,
                // CAM-2c fix §P2: which key fx/fy actually came from - LENS_INTRINSIC_CALIBRATION
                // supplies them directly (no pixel-array size needed); the focal-derived fallback
                // computes them from SENSOR_INFO_PIXEL_ARRAY_SIZE (see the pixel-array-size check
                // above, which already guarantees this branch is never reached without it).
                focalDerivationBasis =
                    if (useLensIntrinsicCalibration) {
                        CameraCalibrationDiagnostics.FOCAL_DERIVATION_BASIS_LENS_INTRINSIC_CALIBRATION
                    } else {
                        CameraCalibrationDiagnostics.FOCAL_DERIVATION_BASIS_PIXEL_ARRAY
                    },
            )
        AnalysisBufferIntrinsicsResolution.Resolved(intrinsics, diagnostics)
    } catch (_: IllegalArgumentException) {
        AnalysisBufferIntrinsicsResolution.InvalidMetadata(AnalysisBufferIntrinsicsInvalidMetadataReason.BUFFER_INTRINSICS_INVALID)
    }
}

/**
 * Resolves [CameraIntrinsicsResolution] for a bound camera, preferring the calibrated,
 * `AnalysisBuffer`-referenced mapping (CAM-2c) over the CAM-1b `PhysicalSensor`-referenced/legacy
 * path.
 *
 * Tries [resolveAnalysisBufferIntrinsics] first; only when that does **not** produce
 * [AnalysisBufferIntrinsicsResolution.Resolved] — for any reason — does this fall back to the
 * unchanged [resolveCameraIntrinsics] (CAM-1b), which itself either resolves a `PhysicalSensor`-
 * referenced `CAMERA_CHARACTERISTICS` value (still correctly rejected by CAM-2a's `projectStars`,
 * unchanged by CAM-2c) or the explicit legacy fallback. Never the reverse, and never a silent
 * downgrade once a calibrated mapping has actually succeeded.
 *
 * [imageWidthPx]/[imageHeightPx] `null` or non-positive (no analyzed frame known yet) skips straight
 * to [resolveCameraIntrinsics], since [resolveAnalysisBufferIntrinsics] requires concrete buffer
 * dimensions to map into — [CameraIntrinsicsResolution.analysisBufferAttempt] is `null` in that case,
 * since no calibrated attempt was made at all (distinct from an attempt that ran and failed).
 *
 * (CAM-2c runtime integration fix) Whatever [resolveAnalysisBufferIntrinsics] actually returned —
 * `Resolved` or one of its explicit failure variants — is always carried on the returned
 * [CameraIntrinsicsResolution.analysisBufferAttempt], even when this function falls through to the
 * CAM-1b path below. A prior revision only ever attached [CameraCalibrationDiagnostics] to a
 * successful result, silently discarding the *reason* a calibrated mapping failed the moment the
 * CAM-1b fallback took over — collapsing e.g. `UnsupportedLogicalMultiCameraMapping` into an
 * indistinguishable "no calibration diagnostics" `null`. [CameraCharacteristicsSnapshot] is captured
 * the same way, independent of outcome, so a diagnostics panel can show the camera metadata behind a
 * rejection too.
 *
 * **One immutable [CameraCharacteristicsSnapshot] per resolution (CAM-2c runtime integration fix
 * P1).** [source] is read via [readSnapshotOrNull] exactly **once**, unconditionally, at the top of
 * this function — never once inside [resolveAnalysisBufferIntrinsics], again directly here, and
 * again inside [resolveCameraIntrinsics], which is what an earlier revision did (up to three
 * independent reads for one resolution). That one resulting snapshot (or `null`, if the read failed)
 * is the exact same value passed to both the calibrated-mapping attempt and the CAM-1b/legacy
 * fallback decision, and is the exact same value returned as
 * [CameraIntrinsicsResolution.cameraCharacteristicsSnapshot] for runtime diagnostics — so the CAM-2c
 * attempt, the CAM-1b fallback (if any), and the HUD are guaranteed to describe the *same* read,
 * never three reads that a real Camera2 implementation merely happens to return equal values for.
 * `CancellationException` from that one read propagates unchanged; any other exception is reported
 * as `snapshot == null` exactly once, with no further retry by either resolver below.
 */
internal fun resolveCameraIntrinsicsPreferringCalibration(
    source: CameraCharacteristicsSource,
    sensorToBufferTransform: SensorToBufferMatrix3?,
    imageWidthPx: Int?,
    imageHeightPx: Int?,
): CameraIntrinsicsResolution {
    val snapshot = source.readSnapshotOrNull()
    val attempt: AnalysisBufferIntrinsicsResolution? =
        if (imageWidthPx != null && imageHeightPx != null && imageWidthPx > 0 && imageHeightPx > 0) {
            resolveAnalysisBufferIntrinsics(snapshot, sensorToBufferTransform, imageWidthPx, imageHeightPx)
        } else {
            null
        }
    if (attempt is AnalysisBufferIntrinsicsResolution.Resolved) {
        return CameraIntrinsicsResolution(
            intrinsics = attempt.intrinsics,
            fallbackReason = null,
            analysisBufferAttempt = attempt,
            cameraCharacteristicsSnapshot = snapshot,
        )
    }
    return resolveCameraIntrinsics(snapshot, imageWidthPx, imageHeightPx)
        .copy(analysisBufferAttempt = attempt, cameraCharacteristicsSnapshot = snapshot)
}
