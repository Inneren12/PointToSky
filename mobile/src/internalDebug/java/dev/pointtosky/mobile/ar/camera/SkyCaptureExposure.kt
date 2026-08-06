package dev.pointtosky.mobile.ar.camera

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import androidx.camera.core.ImageAnalysis
import dev.pointtosky.core.astro.projection.camera.skylog.SkyExposureSample

/**
 * SKY-1 (`internalDebug`-only): reading real exposure/ISO off `CaptureResult`, and asking for a
 * manual long exposure in the first place.
 *
 * ## Why this file exists at all
 * Exposure is not a nice-to-have for the sky stream, it is the difference between usable and useless
 * data. Stars are faint point sources; an auto-exposed frame of the night sky is an almost-black
 * buffer with the few brightest stars smeared by whatever the AE algorithm decided, and a vendor
 * "night mode" additionally stacks and denoises — which destroys exactly the single-frame point
 * spread a detector needs. Nothing in this codebase read exposure or requested a manual one before
 * this change (a grep for `SENSOR_EXPOSURE_TIME`/`CaptureResult` across the camera code came back
 * empty), so both halves are added here.
 *
 * Both halves go through CameraX's `Camera2Interop`, which the app already depends on
 * (`libs.camerax.camera2`, used by `PhysicalCameraBindingExperiment` and `CameraTopologyBuilder`), so
 * this needs no new dependency and no separate Camera2 session.
 *
 * ## What is *not* here
 * No UI policy about which exposure to pick, and no auto-bracketing. [SkyManualExposureRequest] is a
 * plain value the capture screen supplies; choosing a good one for a given sky is a field decision,
 * not something to hard-code.
 */

/** A manual exposure to request for the capture session. */
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
 * What the selected camera will actually let a manual exposure do.
 *
 * [supported] being `false` is a real, reportable outcome — a device whose selected physical camera
 * does not advertise `MANUAL_SENSOR` cannot be driven into a long exposure through the public
 * Camera2 API at all, and a sky session on it will be auto-exposed no matter what is requested.
 * [unsupportedReason] says which check failed so that shows up in the log rather than as a silent
 * fallback to auto.
 */
internal data class SkyManualExposureCapability(
    val supported: Boolean,
    val exposureTimeRangeNanos: LongRange? = null,
    val sensitivityRange: IntRange? = null,
    val unsupportedReason: String? = null,
) {
    /** [request], clamped into the device's advertised ranges, or `null` when manual exposure is unavailable. */
    fun clamp(request: SkyManualExposureRequest): SkyManualExposureRequest? {
        if (!supported) return null
        val exposure =
            exposureTimeRangeNanos
                ?.let { request.exposureTimeNanos.coerceIn(it.first, it.last) }
                ?: request.exposureTimeNanos
        val iso =
            sensitivityRange
                ?.let { request.sensitivityIso.coerceIn(it.first, it.last) }
                ?: request.sensitivityIso
        return SkyManualExposureRequest(exposureTimeNanos = exposure, sensitivityIso = iso)
    }
}

/**
 * Probes [cameraInfo] for manual-exposure support. Never throws: every Camera2 characteristics read
 * here is optional on some device somewhere, and a probe that crashes the experiment is worse than
 * one that reports "unsupported".
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun probeSkyManualExposureCapability(cameraInfo: CameraInfo): SkyManualExposureCapability {
    val camera2Info =
        runCatching { Camera2CameraInfo.from(cameraInfo) }.getOrNull()
            ?: return SkyManualExposureCapability(supported = false, unsupportedReason = "CAMERA2_INFO_UNAVAILABLE")

    val capabilities =
        runCatching {
            camera2Info.getCameraCharacteristic(
                CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
            )
        }.getOrNull()
    if (capabilities == null || !capabilities.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)) {
        return SkyManualExposureCapability(supported = false, unsupportedReason = "MANUAL_SENSOR_CAPABILITY_ABSENT")
    }

    val aeModes =
        runCatching {
            camera2Info.getCameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
        }.getOrNull()
    if (aeModes == null || !aeModes.contains(CameraMetadata.CONTROL_AE_MODE_OFF)) {
        return SkyManualExposureCapability(supported = false, unsupportedReason = "CONTROL_AE_MODE_OFF_UNAVAILABLE")
    }

    val exposureRange =
        runCatching { camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) }
            .getOrNull()
            ?.toLongRangeOrNull()
    val sensitivityRange =
        runCatching { camera2Info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) }
            .getOrNull()
            ?.toIntRangeOrNull()

    return SkyManualExposureCapability(
        supported = true,
        exposureTimeRangeNanos = exposureRange,
        sensitivityRange = sensitivityRange,
    )
}

private fun Range<Long>.toLongRangeOrNull(): LongRange? = if (lower <= upper) lower..upper else null

private fun Range<Int>.toIntRangeOrNull(): IntRange? = if (lower <= upper) lower..upper else null

/**
 * Applies [request] to [builder] as explicit Camera2 capture-request options, and installs
 * [captureCallback] so the *actual* per-frame exposure can be read back.
 *
 * The requested values are never assumed to be what the sensor used — `SENSOR_EXPOSURE_TIME` is
 * clamped by the device and, on some, quietly overridden. The log records what
 * [SkyExposureSampleStore] read from `CaptureResult`, which is the value that actually produced the
 * pixels, and [SkyExposureSample.aeMode] alongside it so a reader can see whether AE really was off.
 *
 * `CONTROL_AE_MODE_OFF` is set first and unconditionally when a manual request is present: without
 * it, `SENSOR_EXPOSURE_TIME` is simply ignored while auto-exposure keeps control.
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun ImageAnalysis.Builder.applySkyCaptureOptions(
    request: SkyManualExposureRequest?,
    captureCallback: android.hardware.camera2.CameraCaptureSession.CaptureCallback,
): ImageAnalysis.Builder {
    val extender = Camera2Interop.Extender(this)
    extender.setSessionCaptureCallback(captureCallback)
    if (request != null) {
        extender.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        extender.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, request.exposureTimeNanos)
        extender.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, request.sensitivityIso)
        // A frame duration shorter than the exposure would silently cap it; asking for the same value
        // lets the sensor actually hold the shutter open for the requested time.
        extender.setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, request.exposureTimeNanos)
    }
    return this
}

/**
 * Keeps the most recent `CaptureResult` exposure readings, keyed by `SENSOR_TIMESTAMP`.
 *
 * ## Why keyed rather than "latest"
 * `CaptureResult` arrives on the camera callback thread, the `ImageProxy` on the analysis executor,
 * and neither ordering is guaranteed. Taking "the most recent result" would attribute one frame's
 * exposure to a different frame's pixels — invisible in the log, and exactly the kind of quiet
 * mis-association a detector built on this data would later be debugged against.
 * `CaptureResult.SENSOR_TIMESTAMP` is the same value as `ImageProxy.imageInfo.timestamp` for the same
 * frame, so keying by it makes the association provable. A frame whose result never arrives records
 * no exposure at all rather than a neighbour's.
 *
 * ## Bounded
 * A ring of [capacity] entries, overwritten oldest-first. A session runs for thousands of frames; an
 * unbounded map would grow without limit for data that is only ever read once, moments after it
 * arrives.
 *
 * Thread-safe: [record] runs on the camera callback thread and [takeFor] on the analysis thread.
 */
internal class SkyExposureSampleStore(
    private val capacity: Int = DEFAULT_CAPACITY,
) {
    init {
        require(capacity > 0) { "capacity must be positive; was $capacity" }
    }

    private val lock = Any()
    private val timestamps = LongArray(capacity)
    private val samples = arrayOfNulls<SkyExposureSample>(capacity)
    private var nextIndex = 0

    /** How many results arrived but carried no `SENSOR_TIMESTAMP` to key them by. */
    var unkeyedResultCount: Long = 0L
        private set

    fun record(result: TotalCaptureResult) = record(skyExposureSampleOf(result))

    /** Visible for tests: the pure half, with a sample already extracted from a `CaptureResult`. */
    fun record(sample: SkyExposureSample) {
        val timestamp = sample.sensorTimestampNanos
        synchronized(lock) {
            if (timestamp == null) {
                unkeyedResultCount += 1
                return
            }
            timestamps[nextIndex] = timestamp
            samples[nextIndex] = sample
            nextIndex = (nextIndex + 1) % capacity
        }
    }

    /**
     * The exposure recorded for exactly [frameTimestampNanos], or `null` when none has arrived (or it
     * has already been evicted). Reading does not consume the entry — a caller that re-reads the same
     * frame gets the same answer.
     */
    fun takeFor(frameTimestampNanos: Long): SkyExposureSample? =
        synchronized(lock) {
            val index = timestamps.indexOfFirst { it == frameTimestampNanos }
            if (index < 0) null else samples[index]
        }

    internal companion object {
        /**
         * Deep enough to absorb the analysis pipeline's own latency (CameraX keeps a small number of
         * frames in flight) with room to spare, small enough to be a fixed, trivial allocation.
         */
        const val DEFAULT_CAPACITY = 16
    }
}

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
 * `"OFF"`, and an unrecognized constant is reported as its raw value rather than guessed at.
 */
internal fun skyAeModeName(mode: Int): String =
    when (mode) {
        CameraMetadata.CONTROL_AE_MODE_OFF -> "OFF"
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
