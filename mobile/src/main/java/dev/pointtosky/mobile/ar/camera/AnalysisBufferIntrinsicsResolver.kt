package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.ActiveArrayIntrinsics
import dev.pointtosky.core.astro.projection.camera.ActiveArraySensorCropRegion
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransform
import dev.pointtosky.core.astro.projection.camera.activeArrayIntrinsicsFromFocalLength
import dev.pointtosky.core.astro.projection.camera.mapActiveArrayIntrinsicsToAnalysisBuffer
import dev.pointtosky.core.astro.projection.camera.toActiveArraySensorCropRegion
import dev.pointtosky.core.astro.projection.camera.toCameraIntrinsics
import kotlinx.coroutines.CancellationException

/**
 * Typed outcome of [resolveAnalysisBufferIntrinsics] (CAM-2c §6) — an explicit result for every way
 * the calibrated Camera2-to-analysis-buffer mapping can succeed or fail, so a caller (and a test)
 * can tell exactly *why* the calibrated path was unavailable rather than being handed a single
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
     * `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` has no exactly-one usable candidate — missing, empty, all
     * invalid, or ambiguous (more than one valid value; see [selectFocalLengthMm]'s own KDoc for why
     * an ambiguous array is never resolved by guessing).
     */
    data object MissingFocalLength : AnalysisBufferIntrinsicsResolution

    /**
     * No usable per-frame sensor-to-buffer mapping was available — `ImageInfo.getSensorToBufferTransformMatrix()`
     * was not axis-aligned (see [androidMatrixToSensorToBufferTransform]), or the caller had none to
     * offer (e.g. resolution requested before any frame was analyzed).
     */
    data object MissingSensorToBufferTransform : AnalysisBufferIntrinsicsResolution

    /**
     * This camera's `REQUEST_AVAILABLE_CAPABILITIES` includes `LOGICAL_MULTI_CAMERA` (CAM-2c §1): its
     * static `CameraCharacteristics` cannot be trusted to describe whichever physical sensor actually
     * produced a given analyzed frame, so no calibrated mapping is attempted at all rather than risk
     * silently mixing metadata from a different physical camera.
     */
    data object UnsupportedLogicalMultiCameraMapping : AnalysisBufferIntrinsicsResolution

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
 * `LENS_INTRINSIC_CALIBRATION` is `[fx, fy, cx, cy, skew]`, five floats, with `fx`/`fy` strictly
 * positive when meaningful. Coordinate-space verification (whether it is safe to read directly as
 * active-array pixels) is a separate check — see [preCorrectionActiveArrayMatchesActiveArray].
 */
private fun isUsableLensIntrinsicCalibration(calibration: FloatArray?): Boolean =
    calibration != null &&
        calibration.size == 5 &&
        calibration.all { it.isFinite() } &&
        calibration[0] > 0f &&
        calibration[1] > 0f

/**
 * `LENS_INTRINSIC_CALIBRATION`'s official contract places it in `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE`
 * coordinates, **not** necessarily `SENSOR_INFO_ACTIVE_ARRAY_SIZE` — a distinct key some devices
 * report when geometric distortion correction shifts/crops the array. Per that same Android
 * contract, when a device does **not** report `SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE` at all,
 * the active array *is* the pre-correction array (no correction applied), so the two coordinate
 * spaces are trivially identical and the calibration is safe to use directly. When the device
 * *does* report one, it must be checked for an exact match — never assumed — before
 * [isUsableLensIntrinsicCalibration]'s numbers are read as active-array pixels (CAM-2c §3 "verified
 * for the active array").
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
 * buffer (CAM-2c). Never silently falls back — every failure mode is one of
 * [AnalysisBufferIntrinsicsResolution]'s explicit variants.
 *
 * Resolution order:
 *  1. Read [source]'s snapshot (any thrown exception → [AnalysisBufferIntrinsicsResolution.InvalidMetadata]).
 *  2. Reject a logical multi-camera outright ([AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping]) —
 *     see [CameraCharacteristicsSnapshot.isLogicalMultiCamera]'s KDoc.
 *  3. Require `SENSOR_INFO_ACTIVE_ARRAY_SIZE` ([AnalysisBufferIntrinsicsResolution.MissingActiveArray]),
 *     `SENSOR_INFO_PHYSICAL_SIZE` ([AnalysisBufferIntrinsicsResolution.MissingPhysicalSensorSize]),
 *     and exactly one valid `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` candidate
 *     ([AnalysisBufferIntrinsicsResolution.MissingFocalLength] — reusing [selectFocalLengthMm]'s
 *     same ambiguity rule as CAM-1b).
 *  4. Require [sensorToBufferTransform] ([AnalysisBufferIntrinsicsResolution.MissingSensorToBufferTransform]).
 *  5. Build active-array intrinsics: `LENS_INTRINSIC_CALIBRATION` when
 *     [isUsableLensIntrinsicCalibration] and [preCorrectionActiveArrayMatchesActiveArray] both hold
 *     (`quality = `[CameraIntrinsicsQuality.CALIBRATED]), else [activeArrayIntrinsicsFromFocalLength]
 *     (`quality = `[CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT]).
 *  6. Map through [sensorToBufferTransform] ([SensorToBufferTransform.toActiveArraySensorCropRegion],
 *     [mapActiveArrayIntrinsicsToAnalysisBuffer]) to the exact [bufferWidthPx]/[bufferHeightPx] buffer.
 *  7. Convert to [CameraIntrinsics] ([toCameraIntrinsics]) with
 *     `source = `[CameraIntrinsicsSource.CAMERA_CHARACTERISTICS]`, `reference =
 *     AnalysisBuffer(`[bufferWidthPx]`, `[bufferHeightPx]`)`.
 *
 * Any [IllegalArgumentException] thrown by the pure `:core:astro-core` math in steps 5-7 is caught
 * and reported as [AnalysisBufferIntrinsicsResolution.InvalidMetadata] — defensive, since the checks
 * above should already rule out the inputs that would cause one.
 */
internal fun resolveAnalysisBufferIntrinsics(
    source: CameraCharacteristicsSource,
    sensorToBufferTransform: SensorToBufferTransform?,
    bufferWidthPx: Int,
    bufferHeightPx: Int,
): AnalysisBufferIntrinsicsResolution {
    val snapshot =
        try {
            source.snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return AnalysisBufferIntrinsicsResolution.InvalidMetadata(AnalysisBufferIntrinsicsInvalidMetadataReason.CHARACTERISTICS_UNAVAILABLE)
        }

    if (snapshot.isLogicalMultiCamera) {
        return AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping
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
    val useLensIntrinsicCalibration =
        isUsableLensIntrinsicCalibration(calibration) && preCorrectionActiveArrayMatchesActiveArray(snapshot)

    val active: ActiveArrayIntrinsics
    val quality: CameraIntrinsicsQuality
    try {
        if (useLensIntrinsicCalibration) {
            checkNotNull(calibration)
            active =
                ActiveArrayIntrinsics(
                    fxPx = calibration[0].toDouble(),
                    fyPx = calibration[1].toDouble(),
                    cxPx = calibration[2].toDouble(),
                    cyPx = calibration[3].toDouble(),
                    widthPx = activeArrayWidthPx,
                    heightPx = activeArrayHeightPx,
                )
            quality = CameraIntrinsicsQuality.CALIBRATED
        } else {
            active =
                activeArrayIntrinsicsFromFocalLength(
                    focalLengthMm = focalLengthMm,
                    sensorWidthMm = sensorWidthMm.toDouble(),
                    sensorHeightMm = sensorHeightMm.toDouble(),
                    activeArrayWidthPx = activeArrayWidthPx,
                    activeArrayHeightPx = activeArrayHeightPx,
                )
            quality = CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT
        }
    } catch (_: IllegalArgumentException) {
        return AnalysisBufferIntrinsicsResolution.InvalidMetadata(AnalysisBufferIntrinsicsInvalidMetadataReason.ACTIVE_ARRAY_INTRINSICS_INVALID)
    }

    val cropRegion: ActiveArraySensorCropRegion
    val bufferValues =
        try {
            cropRegion = sensorToBufferTransform.toActiveArraySensorCropRegion(bufferWidthPx, bufferHeightPx)
            mapActiveArrayIntrinsicsToAnalysisBuffer(active, cropRegion, bufferWidthPx, bufferHeightPx)
        } catch (_: IllegalArgumentException) {
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
                cropRegion = cropRegion,
                bufferValues = bufferValues,
                sensorWidthMm = sensorWidthMm.toDouble(),
                sensorHeightMm = sensorHeightMm.toDouble(),
                focalLengthMm = focalLengthMm,
                quality = quality,
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
 * dimensions to map into.
 */
internal fun resolveCameraIntrinsicsPreferringCalibration(
    source: CameraCharacteristicsSource,
    sensorToBufferTransform: SensorToBufferTransform?,
    imageWidthPx: Int?,
    imageHeightPx: Int?,
): CameraIntrinsicsResolution {
    if (imageWidthPx != null && imageHeightPx != null && imageWidthPx > 0 && imageHeightPx > 0) {
        val calibrated = resolveAnalysisBufferIntrinsics(source, sensorToBufferTransform, imageWidthPx, imageHeightPx)
        if (calibrated is AnalysisBufferIntrinsicsResolution.Resolved) {
            return CameraIntrinsicsResolution(
                intrinsics = calibrated.intrinsics,
                fallbackReason = null,
                calibrationDiagnostics = calibrated.diagnostics,
            )
        }
    }
    return resolveCameraIntrinsics(source, imageWidthPx, imageHeightPx)
}
