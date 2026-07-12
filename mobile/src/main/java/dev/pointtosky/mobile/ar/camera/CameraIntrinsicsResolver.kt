package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.horizontalFovDeg
import dev.pointtosky.core.astro.projection.camera.legacyFallbackCameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.verticalFovDeg
import kotlinx.coroutines.CancellationException

/**
 * Diagnostic reasons [resolveCameraIntrinsics] falls back to
 * [dev.pointtosky.core.astro.projection.camera.legacyFallbackCameraIntrinsics]. Deliberately short,
 * non-device-specific strings — never a raw exception message or stack trace — so they are safe to
 * surface in logs/telemetry without leaking device metadata.
 */
internal object CameraIntrinsicsFallbackReason {
    const val CHARACTERISTICS_UNAVAILABLE = "camera_characteristics_unavailable"
    const val NO_VALID_FOCAL_LENGTH = "no_valid_focal_length"
    const val AMBIGUOUS_FOCAL_LENGTH = "ambiguous_focal_length"
    const val MISSING_OR_INVALID_SENSOR_SIZE = "missing_or_invalid_sensor_size"
    const val COMPUTED_INTRINSICS_INVALID = "computed_intrinsics_invalid"
}

/** Outcome of [selectFocalLengthMm]: exactly one usable candidate, none, or more than one. */
internal sealed interface FocalLengthSelection {
    data class Resolved(val focalLengthMm: Double) : FocalLengthSelection

    data object NoneValid : FocalLengthSelection

    data object Ambiguous : FocalLengthSelection
}

/**
 * Selects one focal length (millimetres) from a Camera2 `LENS_INFO_AVAILABLE_FOCAL_LENGTHS`
 * candidate array.
 *
 * Most phone main cameras report a single fixed focal length, which is the only case
 * [resolveCameraIntrinsics] can safely label [CameraIntrinsicsSource.CAMERA_CHARACTERISTICS]:
 * static `CameraCharacteristics` has no field describing which of several reported focal lengths
 * the currently bound capture stream is actually using (that requires a live `CaptureResult`,
 * which is out of scope for CAM-1b — no capture pipeline exists yet). Picking a value out of
 * several candidates — even deterministically, e.g. the minimum — would risk mislabeling a guess
 * as calibrated metadata, so any array with more than one valid entry is reported as
 * [FocalLengthSelection.Ambiguous] and [resolveCameraIntrinsics] falls back instead of guessing.
 */
internal fun selectFocalLengthMm(candidates: FloatArray?): FocalLengthSelection {
    if (candidates == null) return FocalLengthSelection.NoneValid
    val valid = candidates.filter { it.isFinite() && it > 0f }
    return when (valid.size) {
        0 -> FocalLengthSelection.NoneValid
        1 -> FocalLengthSelection.Resolved(valid.single().toDouble())
        else -> FocalLengthSelection.Ambiguous
    }
}

/**
 * Resolves [CameraIntrinsicsResolution] from a [CameraCharacteristicsSource], isolated from the
 * real Camera2/CameraX API so it is unit-testable with fake metadata (CAM-1b).
 *
 * Resolution order: read characteristics -> select a focal length -> read sensor physical size ->
 * compute horizontal/vertical FOV with the pure `:core:astro-core` formula -> build a calibrated
 * [CameraIntrinsics]. Any failure along the way (missing/invalid/malformed metadata, or an
 * exception while reading it) returns an explicit [CameraIntrinsicsSource.LEGACY_FALLBACK] result
 * with a [CameraIntrinsicsResolution.fallbackReason] instead of guessing or clamping.
 */
internal fun resolveCameraIntrinsics(
    source: CameraCharacteristicsSource,
    imageWidthPx: Int?,
    imageHeightPx: Int?,
): CameraIntrinsicsResolution {
    fun fallback(reason: String) =
        CameraIntrinsicsResolution(
            intrinsics = legacyFallbackCameraIntrinsics(imageWidthPx, imageHeightPx),
            fallbackReason = reason,
        )

    val snapshot =
        try {
            source.snapshot()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return fallback(CameraIntrinsicsFallbackReason.CHARACTERISTICS_UNAVAILABLE)
        }

    val focalLengthMm =
        when (val selection = selectFocalLengthMm(snapshot.availableFocalLengthsMm)) {
            is FocalLengthSelection.Resolved -> selection.focalLengthMm
            FocalLengthSelection.NoneValid -> return fallback(CameraIntrinsicsFallbackReason.NO_VALID_FOCAL_LENGTH)
            FocalLengthSelection.Ambiguous -> return fallback(CameraIntrinsicsFallbackReason.AMBIGUOUS_FOCAL_LENGTH)
        }

    val sensorWidthMm = snapshot.sensorPhysicalWidthMm
    val sensorHeightMm = snapshot.sensorPhysicalHeightMm
    if (sensorWidthMm == null || sensorHeightMm == null ||
        !sensorWidthMm.isFinite() || !sensorHeightMm.isFinite() ||
        sensorWidthMm <= 0f || sensorHeightMm <= 0f
    ) {
        return fallback(CameraIntrinsicsFallbackReason.MISSING_OR_INVALID_SENSOR_SIZE)
    }

    return try {
        val intrinsics =
            CameraIntrinsics(
                horizontalFovDeg = horizontalFovDeg(sensorWidthMm.toDouble(), focalLengthMm),
                verticalFovDeg = verticalFovDeg(sensorHeightMm.toDouble(), focalLengthMm),
                focalLengthMm = focalLengthMm,
                sensorWidthMm = sensorWidthMm.toDouble(),
                sensorHeightMm = sensorHeightMm.toDouble(),
                principalPointXPx = null,
                principalPointYPx = null,
                source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
            )
        CameraIntrinsicsResolution(intrinsics = intrinsics, fallbackReason = null)
    } catch (_: IllegalArgumentException) {
        fallback(CameraIntrinsicsFallbackReason.COMPUTED_INTRINSICS_INVALID)
    }
}
