package dev.pointtosky.mobile.ar.camera

import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageAnalysis
import dev.pointtosky.core.astro.projection.camera.skylog.SkyExposureSample
import dev.pointtosky.core.astro.projection.camera.skylog.SkyObserverContext

/**
 * SKY-1 (`internalDebug`-only): asking for a manual long exposure, reading back what the sensor
 * actually did, and refusing to record when the two do not line up.
 *
 * ## Why this file exists at all
 * Exposure is not a nice-to-have for the sky stream, it is the difference between usable and useless
 * data. Stars are faint point sources; an auto-exposed frame of the night sky is an almost-black
 * buffer with the few brightest stars smeared by whatever the AE algorithm decided, and a vendor
 * "night mode" additionally stacks and denoises — which destroys exactly the single-frame point
 * spread a detector needs. Nothing in this codebase read exposure or requested a manual one before
 * SKY-1 (a grep for `SENSOR_EXPOSURE_TIME`/`CaptureResult` across the camera code came back empty).
 *
 * ## The gate, not just the request
 * Setting `CONTROL_AE_MODE_OFF` is a *request*. Devices clamp it, ignore it, or apply it a few frames
 * late. So nothing here treats the request as proof: [evaluateSkyRecordingGate] decides whether a
 * session may start at all, and [validateSkyManualExposure] decides, per frame, whether the
 * `CaptureResult` that actually produced those pixels is one this dataset accepts. A frame whose
 * result says AE was still on is dropped with a typed reason, not recorded with a misleading
 * `aeMode` field.
 *
 * Both halves go through CameraX's `Camera2Interop`, which the app already depends on
 * (`libs.camerax.camera2`, used by `PhysicalCameraBindingExperiment` and `CameraTopologyBuilder`), so
 * this needs no new dependency and no separate Camera2 session. The interop's opt-in is declared twice
 * per entry point — `kotlin.OptIn` for the compiler, `androidx.annotation.OptIn` for Android Lint's
 * `UnsafeOptInUsageError`, which does not read the Kotlin one; see `SkyCaptureClock`.
 */

/** What the operator asked for, before the device has been consulted. */
internal data class SkyManualExposureRequest(
    val exposureTimeNanos: Long,
    val sensitivityIso: Int,
) {
    init {
        require(exposureTimeNanos > 0L) { "exposureTimeNanos must be positive; was $exposureTimeNanos" }
        require(sensitivityIso > 0) { "sensitivityIso must be positive; was $sensitivityIso" }
    }
}

/**
 * A request the device has confirmed it can express, with the frame duration worked out.
 *
 * [frameDurationNanos] is carried explicitly rather than assumed equal to [exposureTimeNanos].
 * Camera2 requires `SENSOR_FRAME_DURATION >= SENSOR_EXPOSURE_TIME` **and**
 * `<= SENSOR_INFO_MAX_FRAME_DURATION`; setting a frame duration the device rejects makes it drop the
 * whole manual request and quietly fall back to auto — which is exactly the failure this dataset must
 * never silently absorb. See [SkyManualExposureCapability.resolve].
 */
internal data class SkyResolvedExposure(
    val exposureTimeNanos: Long,
    val sensitivityIso: Int,
    val frameDurationNanos: Long,
) {
    init {
        require(exposureTimeNanos > 0L) { "exposureTimeNanos must be positive; was $exposureTimeNanos" }
        require(sensitivityIso > 0) { "sensitivityIso must be positive; was $sensitivityIso" }
        require(frameDurationNanos >= exposureTimeNanos) {
            "frameDurationNanos ($frameDurationNanos) must be >= exposureTimeNanos ($exposureTimeNanos)"
        }
    }
}

/** Why a camera cannot be driven into a manual exposure at all. */
internal enum class SkyManualExposureUnsupportedReason {
    /** `Camera2CameraInfo.from()` could not read the bound camera. */
    CAMERA2_INFO_UNAVAILABLE,

    /** `REQUEST_AVAILABLE_CAPABILITIES` does not contain `MANUAL_SENSOR`. */
    MANUAL_SENSOR_CAPABILITY_ABSENT,

    /** `CONTROL_AE_AVAILABLE_MODES` does not contain `CONTROL_AE_MODE_OFF`. */
    CONTROL_AE_MODE_OFF_UNAVAILABLE,
}

/** Why a supported camera still could not express one specific request. */
internal enum class SkyExposureUnresolvableReason {
    MANUAL_EXPOSURE_UNSUPPORTED,

    /** The advertised exposure range and max frame duration leave no legal exposure time. */
    EXPOSURE_RANGE_UNSATISFIABLE,

    /** The advertised sensitivity range is empty or non-positive. */
    SENSITIVITY_RANGE_UNSATISFIABLE,

    /** No frame duration satisfies both `>= exposure` and `<= SENSOR_INFO_MAX_FRAME_DURATION`. */
    FRAME_DURATION_UNSATISFIABLE,
}

/** The outcome of asking a camera to express one [SkyManualExposureRequest]. */
internal sealed interface SkyExposureResolution {
    data class Resolved(
        val exposure: SkyResolvedExposure,
    ) : SkyExposureResolution

    data class Unresolvable(
        val reason: SkyExposureUnresolvableReason,
    ) : SkyExposureResolution
}

/**
 * What the selected camera will actually let a manual exposure do.
 *
 * [supported] being `false` is a real, reportable outcome — a device whose selected physical camera
 * does not advertise `MANUAL_SENSOR` cannot be driven into a long exposure through the public Camera2
 * API at all, and a sky session on it will be auto-exposed no matter what is requested.
 *
 * [maxFrameDurationNanos] is `SENSOR_INFO_MAX_FRAME_DURATION`. `null` means the device did not report
 * it, in which case [resolve] leaves the frame duration equal to the exposure time and says so — it
 * does not invent a bound.
 *
 * **Known gap, deliberately not guessed at:** Camera2 also imposes a *minimum* frame duration that
 * depends on the configured output size (`StreamConfigurationMap.getOutputMinFrameDuration`), which
 * is only knowable once the exact `ImageAnalysis` surface is chosen. [minFrameDurationNanos] carries
 * it when a caller can supply it and is `null` otherwise; a `null` is never treated as zero-is-fine,
 * it simply means the resolved frame duration is the exposure time and nothing stronger is claimed.
 */
internal data class SkyManualExposureCapability(
    val supported: Boolean,
    val exposureTimeRangeNanos: LongRange? = null,
    val sensitivityRange: IntRange? = null,
    val maxFrameDurationNanos: Long? = null,
    val minFrameDurationNanos: Long? = null,
    val unsupportedReason: SkyManualExposureUnsupportedReason? = null,
) {
    init {
        require(supported == (unsupportedReason == null)) {
            "unsupportedReason must be present exactly when unsupported; was supported=$supported, reason=$unsupportedReason"
        }
    }

    /**
     * [request] expressed within everything this device advertises, or why it cannot be.
     *
     * Order matters: the exposure time is clamped into the advertised range **and then** capped at
     * [maxFrameDurationNanos], because a sensor cannot hold the shutter open longer than the longest
     * frame it can produce. Capping second means a device whose max frame duration is shorter than its
     * own advertised minimum exposure is reported as [SkyExposureUnresolvableReason.EXPOSURE_RANGE_UNSATISFIABLE]
     * rather than silently resolved to an illegal value.
     */
    fun resolve(request: SkyManualExposureRequest): SkyExposureResolution {
        if (!supported) {
            return SkyExposureResolution.Unresolvable(SkyExposureUnresolvableReason.MANUAL_EXPOSURE_UNSUPPORTED)
        }

        var exposure =
            exposureTimeRangeNanos?.let { request.exposureTimeNanos.coerceIn(it.first, it.last) }
                ?: request.exposureTimeNanos
        if (maxFrameDurationNanos != null && exposure > maxFrameDurationNanos) {
            exposure = maxFrameDurationNanos
        }
        if (exposure <= 0L || (exposureTimeRangeNanos != null && exposure < exposureTimeRangeNanos.first)) {
            return SkyExposureResolution.Unresolvable(SkyExposureUnresolvableReason.EXPOSURE_RANGE_UNSATISFIABLE)
        }

        val iso = sensitivityRange?.let { request.sensitivityIso.coerceIn(it.first, it.last) } ?: request.sensitivityIso
        if (iso <= 0) {
            return SkyExposureResolution.Unresolvable(SkyExposureUnresolvableReason.SENSITIVITY_RANGE_UNSATISFIABLE)
        }

        val frameDuration = maxOf(exposure, minFrameDurationNanos ?: 0L)
        if (maxFrameDurationNanos != null && frameDuration > maxFrameDurationNanos) {
            return SkyExposureResolution.Unresolvable(SkyExposureUnresolvableReason.FRAME_DURATION_UNSATISFIABLE)
        }

        return SkyExposureResolution.Resolved(
            SkyResolvedExposure(exposureTimeNanos = exposure, sensitivityIso = iso, frameDurationNanos = frameDuration),
        )
    }
}

/**
 * Probes [cameraInfo] for manual-exposure support. Never throws: every Camera2 characteristics read
 * here is optional on some device somewhere, and a probe that crashes the experiment is worse than
 * one that reports "unsupported".
 */
@OptIn(ExperimentalCamera2Interop::class)
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
internal fun probeSkyManualExposureCapability(cameraInfo: CameraInfo): SkyManualExposureCapability {
    val camera2Info =
        runCatching { Camera2CameraInfo.from(cameraInfo) }.getOrNull()
            ?: return unsupported(SkyManualExposureUnsupportedReason.CAMERA2_INFO_UNAVAILABLE)

    val capabilities =
        runCatching {
            camera2Info.getCameraCharacteristic(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            )
        }.getOrNull()
    if (capabilities == null || !capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
        return unsupported(SkyManualExposureUnsupportedReason.MANUAL_SENSOR_CAPABILITY_ABSENT)
    }

    val aeModes =
        runCatching {
            camera2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
        }.getOrNull()
    if (aeModes == null || !aeModes.contains(CameraMetadata.CONTROL_AE_MODE_OFF)) {
        return unsupported(SkyManualExposureUnsupportedReason.CONTROL_AE_MODE_OFF_UNAVAILABLE)
    }

    return SkyManualExposureCapability(
        supported = true,
        exposureTimeRangeNanos =
            runCatching { camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) }
                .getOrNull()
                ?.toLongRangeOrNull(),
        sensitivityRange =
            runCatching { camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) }
                .getOrNull()
                ?.toIntRangeOrNull(),
        maxFrameDurationNanos =
            runCatching { camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION) }
                .getOrNull()
                ?.takeIf { it > 0L },
    )
}

private fun unsupported(reason: SkyManualExposureUnsupportedReason): SkyManualExposureCapability =
    SkyManualExposureCapability(supported = false, unsupportedReason = reason)

private fun Range<Long>.toLongRangeOrNull(): LongRange? = if (lower in 1..upper) lower..upper else null

private fun Range<Int>.toIntRangeOrNull(): IntRange? = if (lower in 1..upper) lower..upper else null

/**
 * Applies [exposure] to [builder] as explicit Camera2 capture-request options, and installs
 * [captureCallback] so the *actual* per-frame exposure can be read back.
 *
 * `CONTROL_AE_MODE_OFF` is set first and unconditionally when an exposure is present: without it,
 * `SENSOR_EXPOSURE_TIME` is simply ignored while auto-exposure keeps control.
 *
 * A `null` [exposure] installs only the callback. That is the state before the capability probe has
 * run: the first bind of a camera must **not** carry an unvalidated default request, because a
 * request outside the device's range makes it discard the manual mode entirely and the session would
 * silently be auto-exposed. See `SkySessionCaptureScreen`'s two-phase bind.
 */
@OptIn(ExperimentalCamera2Interop::class)
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
internal fun ImageAnalysis.Builder.applySkyCaptureOptions(
    exposure: SkyResolvedExposure?,
    captureCallback: CameraCaptureSession.CaptureCallback,
): ImageAnalysis.Builder {
    val extender = Camera2Interop.Extender(this)
    extender.setSessionCaptureCallback(captureCallback)
    if (exposure != null) {
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.exposureTimeNanos)
        extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, exposure.sensitivityIso)
        extender.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, exposure.frameDurationNanos)
    }
    return this
}

// -------------------------------------------------------------------------------------------------
// The recording gate
// -------------------------------------------------------------------------------------------------

/** Why recording may not start. Every value is something the HUD can state plainly to the operator. */
internal enum class SkyRecordingBlockedReason {
    /** Auto exposure. The SKY-1 dataset is a manual-exposure dataset; an auto-exposed session is not it. */
    AUTO_EXPOSURE_NOT_ALLOWED,

    /** No camera has been bound yet, so nothing is known about what it can do. */
    CAMERA_CAPABILITY_UNKNOWN,

    CAMERA2_INFO_UNAVAILABLE,
    MANUAL_SENSOR_CAPABILITY_ABSENT,
    CONTROL_AE_MODE_OFF_UNAVAILABLE,
    EXPOSURE_RANGE_UNSATISFIABLE,
    SENSITIVITY_RANGE_UNSATISFIABLE,
    FRAME_DURATION_UNSATISFIABLE,

    /** The resolved exposure has not been bound yet — the session is still running the pre-probe bind. */
    EXPOSURE_NOT_APPLIED_YET,

    /** The session's camera intrinsics resolve on the first analyzed frame; none has arrived. */
    INTRINSICS_NOT_RESOLVED,

    /**
     * There is no observing context: no location permission, no fix yet, or no magnetic declination for
     * the fix there is.
     *
     * A session recorded in this state is *useless for its stated purpose*. Every frame would carry
     * `observer: null`, no star could be projected into it, and the offline replay skips every such
     * frame with `OBSERVER_CONTEXT_UNAVAILABLE` — a directory full of pixels a detector cannot be
     * developed against. Blocking the start is what stops an operator from spending a clear night
     * filling storage with that.
     *
     * Per-frame dropping ([SkyRecordOutcome.OBSERVER_CONTEXT_UNAVAILABLE]) still exists underneath, for
     * a fix that disappears mid-session. It is a backstop, not the gate: on its own it would let a
     * whole session record zero usable frames while the HUD counted them as dropped.
     */
    OBSERVER_CONTEXT_UNAVAILABLE,
}

/**
 * Whether [observer] carries everything the projection needs — a location *and* the magnetic
 * declination at it.
 *
 * Both halves are required because both are required downstream: `SkyObserverContext.toStarProjectionContext`
 * returns `null` without a declination, and the replay skips such a frame with
 * `MAGNETIC_DECLINATION_UNAVAILABLE`. A missing declination is never substituted with `0.0` — "magnetic
 * north is close enough to true north" and "the declination is known to be zero" are different claims,
 * and a dataset that conflates them is one nobody can trust afterwards.
 *
 * There is deliberately no coordinate fallback of any kind: no `(0, 0)`, no last-known-good, no
 * device-default. Absent stays absent.
 */
internal fun isUsableSkyObserverContext(observer: SkyObserverContext?): Boolean =
    observer != null && observer.magneticDeclinationDeg != null

/** Whether a session may start recording, and with which confirmed exposure. */
internal sealed interface SkyRecordingGate {
    data class Allowed(
        val exposure: SkyResolvedExposure,
    ) : SkyRecordingGate

    data class Blocked(
        val reason: SkyRecordingBlockedReason,
    ) : SkyRecordingGate
}

/**
 * Decides whether recording may start.
 *
 * [appliedExposure] is the exposure the **currently bound** session was configured with, not the one
 * the operator most recently picked. Requiring them to be the same object-equal value is what stops a
 * session from being recorded at one exposure while the HUD shows another: a newly chosen exposure
 * only becomes recordable after the rebind that actually applied it.
 *
 * [observer] is the observing context a frame captured *now* would carry. It is checked here, before
 * any byte is written, rather than only per frame: see
 * [SkyRecordingBlockedReason.OBSERVER_CONTEXT_UNAVAILABLE].
 */
internal fun evaluateSkyRecordingGate(
    requested: SkyManualExposureRequest?,
    capability: SkyManualExposureCapability?,
    appliedExposure: SkyResolvedExposure?,
    intrinsicsResolved: Boolean,
    observer: SkyObserverContext?,
): SkyRecordingGate {
    if (requested == null) return SkyRecordingGate.Blocked(SkyRecordingBlockedReason.AUTO_EXPOSURE_NOT_ALLOWED)
    if (capability == null) return SkyRecordingGate.Blocked(SkyRecordingBlockedReason.CAMERA_CAPABILITY_UNKNOWN)

    val resolution = capability.resolve(requested)
    if (resolution is SkyExposureResolution.Unresolvable) {
        return SkyRecordingGate.Blocked(resolution.reason.toBlockedReason(capability.unsupportedReason))
    }
    val resolved = (resolution as SkyExposureResolution.Resolved).exposure

    if (appliedExposure != resolved) return SkyRecordingGate.Blocked(SkyRecordingBlockedReason.EXPOSURE_NOT_APPLIED_YET)
    if (!intrinsicsResolved) return SkyRecordingGate.Blocked(SkyRecordingBlockedReason.INTRINSICS_NOT_RESOLVED)
    if (!isUsableSkyObserverContext(observer)) {
        return SkyRecordingGate.Blocked(SkyRecordingBlockedReason.OBSERVER_CONTEXT_UNAVAILABLE)
    }
    return SkyRecordingGate.Allowed(resolved)
}

private fun SkyExposureUnresolvableReason.toBlockedReason(
    unsupportedReason: SkyManualExposureUnsupportedReason?,
): SkyRecordingBlockedReason =
    when (this) {
        SkyExposureUnresolvableReason.MANUAL_EXPOSURE_UNSUPPORTED ->
            when (unsupportedReason) {
                SkyManualExposureUnsupportedReason.CAMERA2_INFO_UNAVAILABLE ->
                    SkyRecordingBlockedReason.CAMERA2_INFO_UNAVAILABLE
                SkyManualExposureUnsupportedReason.CONTROL_AE_MODE_OFF_UNAVAILABLE ->
                    SkyRecordingBlockedReason.CONTROL_AE_MODE_OFF_UNAVAILABLE

                SkyManualExposureUnsupportedReason.MANUAL_SENSOR_CAPABILITY_ABSENT, null ->
                    SkyRecordingBlockedReason.MANUAL_SENSOR_CAPABILITY_ABSENT
            }

        SkyExposureUnresolvableReason.EXPOSURE_RANGE_UNSATISFIABLE ->
            SkyRecordingBlockedReason.EXPOSURE_RANGE_UNSATISFIABLE

        SkyExposureUnresolvableReason.SENSITIVITY_RANGE_UNSATISFIABLE ->
            SkyRecordingBlockedReason.SENSITIVITY_RANGE_UNSATISFIABLE

        SkyExposureUnresolvableReason.FRAME_DURATION_UNSATISFIABLE ->
            SkyRecordingBlockedReason.FRAME_DURATION_UNSATISFIABLE
    }

// -------------------------------------------------------------------------------------------------
// Per-frame validation
// -------------------------------------------------------------------------------------------------

/** Why one frame's matched `CaptureResult` disqualifies it from this dataset. */
internal enum class SkyExposureRejectReason {
    EXPOSURE_TIME_MISSING,
    SENSITIVITY_MISSING,

    /** The result's `SENSOR_TIMESTAMP` is not this frame's timestamp — it belongs to a different frame. */
    SENSOR_TIMESTAMP_MISMATCH,

    /** Auto-exposure was still in control when these pixels were produced. */
    AE_MODE_NOT_OFF,
}

/** Whether one frame's matched exposure is acceptable for the manual-exposure dataset. */
internal sealed interface SkyExposureValidation {
    data object Accepted : SkyExposureValidation

    data class Rejected(
        val reason: SkyExposureRejectReason,
    ) : SkyExposureValidation
}

/**
 * Validates one frame's matched `CaptureResult` against what the SKY-1 dataset requires.
 *
 * The `SENSOR_TIMESTAMP` equality check is redundant with the join that produced the pair (see
 * [SkyExposureJoin]) and is kept anyway: it is the single assertion that the shutter numbers in a log
 * line belong to the pixels beside them, and a redundant check that can never fire costs nothing
 * compared to a dataset that is quietly wrong.
 */
internal fun validateSkyManualExposure(
    sample: SkyExposureSample,
    frameTimestampNanos: Long,
): SkyExposureValidation =
    when {
        sample.sensorTimestampNanos != frameTimestampNanos ->
            SkyExposureValidation.Rejected(SkyExposureRejectReason.SENSOR_TIMESTAMP_MISMATCH)

        sample.exposureTimeNanos == null ->
            SkyExposureValidation.Rejected(
                SkyExposureRejectReason.EXPOSURE_TIME_MISSING,
            )
        sample.sensitivityIso == null -> SkyExposureValidation.Rejected(SkyExposureRejectReason.SENSITIVITY_MISSING)
        sample.aeMode != SKY_AE_MODE_OFF -> SkyExposureValidation.Rejected(SkyExposureRejectReason.AE_MODE_NOT_OFF)
        else -> SkyExposureValidation.Accepted
    }

/** The one spelling of "auto-exposure was off" that both the writer and any offline reader compare against. */
internal const val SKY_AE_MODE_OFF = "OFF"

/**
 * Extracts the exposure fields from one `CaptureResult`. Every key is optional per the Camera2
 * contract, so every field is nullable — "the device did not report it" and "it was zero" must not
 * look the same in the log.
 */
internal fun skyExposureSampleOf(result: CaptureResult): SkyExposureSample =
    SkyExposureSample(
        exposureTimeNanos = result.get(CaptureResult.SENSOR_EXPOSURE_TIME),
        sensitivityIso = result.get(CaptureResult.SENSOR_SENSITIVITY),
        frameDurationNanos = result.get(CaptureResult.SENSOR_FRAME_DURATION),
        aeMode = result.get(CaptureResult.CONTROL_AE_MODE)?.let { skyAeModeName(it) },
        awbMode = result.get(CaptureResult.CONTROL_AWB_MODE)?.let { skyAwbModeName(it) },
        sensorTimestampNanos = result.get(CaptureResult.SENSOR_TIMESTAMP),
    )

/**
 * The `CONTROL_AE_MODE` constant's own name. A stable identifier, never a localized or
 * `toString()`-derived string — an offline reader checking "was AE actually off?" compares against
 * [SKY_AE_MODE_OFF], and an unrecognized constant is reported as its raw value rather than guessed at.
 */
internal fun skyAeModeName(mode: Int): String =
    when (mode) {
        CameraMetadata.CONTROL_AE_MODE_OFF -> SKY_AE_MODE_OFF
        CameraMetadata.CONTROL_AE_MODE_ON -> "ON"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH -> "ON_AUTO_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH -> "ON_ALWAYS_FLASH"
        CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH_REDEYE -> "ON_AUTO_FLASH_REDEYE"
        CameraMetadata.CONTROL_AE_MODE_ON_EXTERNAL_FLASH -> "ON_EXTERNAL_FLASH"
        else -> "UNKNOWN_$mode"
    }

/** The `CONTROL_AWB_MODE` constant's own name; see [skyAeModeName] for why this is not a `toString()`. */
internal fun skyAwbModeName(mode: Int): String =
    when (mode) {
        CameraMetadata.CONTROL_AWB_MODE_OFF -> "OFF"
        CameraMetadata.CONTROL_AWB_MODE_AUTO -> "AUTO"
        CameraMetadata.CONTROL_AWB_MODE_INCANDESCENT -> "INCANDESCENT"
        CameraMetadata.CONTROL_AWB_MODE_FLUORESCENT -> "FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_WARM_FLUORESCENT -> "WARM_FLUORESCENT"
        CameraMetadata.CONTROL_AWB_MODE_DAYLIGHT -> "DAYLIGHT"
        CameraMetadata.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT -> "CLOUDY_DAYLIGHT"
        CameraMetadata.CONTROL_AWB_MODE_TWILIGHT -> "TWILIGHT"
        CameraMetadata.CONTROL_AWB_MODE_SHADE -> "SHADE"
        else -> "UNKNOWN_$mode"
    }
