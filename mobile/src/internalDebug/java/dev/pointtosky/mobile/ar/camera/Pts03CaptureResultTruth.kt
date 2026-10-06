package dev.pointtosky.mobile.ar.camera

import android.graphics.Rect
import android.hardware.camera2.CaptureResult
import android.os.Build
import dev.pointtosky.core.astro.projection.camera.skylog.SkyExposureSample

/**
 * PTS-03 (`internalDebug`-only): the per-`CaptureResult` camera-truth metadata the Pixel 9 experiment
 * needs, carried **beside** — never inside — SKY-1's narrow [SkyExposureSample].
 *
 * ## One result, one snapshot
 * [SkyCaptureResultSnapshot] is built by [skyCaptureResultSnapshotOf] from exactly one `CaptureResult`
 * object, in one call, on the camera callback thread, before the result is posted to the analysis
 * executor. Its exposure half and its PTS-03 half therefore cannot describe different frames: there is
 * no separate AF store, physical-ID store or distortion store that a later read could pair with the
 * wrong frame. The snapshot travels through [SkyExposureJoin] as one immutable value and reaches the
 * consumer only alongside the `ImageProxy` whose `imageInfo.timestamp` equals its `SENSOR_TIMESTAMP`.
 *
 * ## Why a field enum and a reader
 * `CaptureResult.Key` constants are `null` under the unit-test `android.jar` stub, and `Rect` cannot
 * be constructed there, so key-keyed fakes are impossible on the JVM. Extraction is therefore split:
 * - [Pts03CaptureResultReader] answers "what plain value did the result carry for this
 *   [Pts03CaptureResultField]?" — the only Android-facing part ([Camera2CaptureResultReader]);
 * - [pts03CaptureTruthOf] is pure: API gating, enum naming, immutable copies, null semantics.
 *
 * ## Null means "not reported"
 * Every optional Camera2 key may be absent on some HAL. `null` is never a stand-in for zero or for a
 * default mode, and an API-gated key distinguishes "this API level cannot report it"
 * ([Pts03KeyAvailability.API_UNSUPPORTED]) from "the device did not report it"
 * ([Pts03KeyAvailability.NOT_REPORTED]).
 */

/** Every per-result key PTS-03 reads, with the API level at which Camera2 defines it. */
internal enum class Pts03CaptureResultField(
    val minSdk: Int,
) {
    SENSOR_TIMESTAMP(21),
    LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID(29),
    DISTORTION_CORRECTION_MODE(28),
    LENS_FOCAL_LENGTH(21),
    LENS_INTRINSIC_CALIBRATION(23),
    LENS_FOCUS_DISTANCE(21),
    LENS_STATE(21),
    CONTROL_AF_MODE(21),
    CONTROL_AF_STATE(21),
    SCALER_CROP_REGION(21),
    CONTROL_ZOOM_RATIO(30),
    LENS_OPTICAL_STABILIZATION_MODE(21),
    CONTROL_VIDEO_STABILIZATION_MODE(21),
    HOT_PIXEL_MODE(21),
    NOISE_REDUCTION_MODE(21),
    EDGE_MODE(21),
}

/**
 * Reads one [Pts03CaptureResultField] as a plain JVM value: `Long`, `Int`, `Float`, `String`,
 * `FloatArray` or [Pts03IntRect]. Returns `null` when the key is absent. Implementations must never
 * throw; [pts03CaptureTruthOf] additionally tolerates a value of an unexpected type by treating it as
 * absent rather than crashing the experiment.
 */
internal fun interface Pts03CaptureResultReader {
    fun read(field: Pts03CaptureResultField): Any?
}

/** An immutable copy of an `android.graphics.Rect`, in the coordinate system Camera2 documents for the key. */
internal data class Pts03IntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Whether an API-gated key could have been reported at all. */
internal enum class Pts03KeyAvailability {
    REPORTED,

    /** The key exists at this API level but the result did not carry it. */
    NOT_REPORTED,

    /** The running API level predates the key; reading it was never attempted. */
    API_UNSUPPORTED,
}

/**
 * One Camera2 enum value: the raw integer exactly as reported plus the constant's own name.
 * An unrecognised integer keeps its raw value and is named `UNKNOWN_<raw>` — never coerced to a known mode.
 */
internal data class Pts03EnumValue(
    val raw: Int,
    val name: String,
)

/**
 * The PTS-03 half of one `CaptureResult`. Exposure, ISO and frame duration are **not** duplicated here:
 * they live in the paired [SkyExposureSample], read from the same result object.
 *
 * @property sensorTimestampNanos `SENSOR_TIMESTAMP`, the join key. Read independently from the same
 *   result as [SkyExposureSample.sensorTimestampNanos]; [SkyCaptureResultSnapshot] requires the two to agree.
 * @property lensIntrinsicCalibration `[fx, fy, cx, cy, s]` as reported per frame, copied into an immutable list.
 * @property scalerCropRegion `SCALER_CROP_REGION`; its basis depends on [distortionCorrectionMode]
 *   (pre-correction array for `OFF`, active array otherwise) — see [Pts03MetadataCoordinateBasis].
 */
internal data class Pts03CaptureTruth(
    val sensorTimestampNanos: Long?,
    val activePhysicalCameraId: String?,
    val activePhysicalCameraIdAvailability: Pts03KeyAvailability,
    val distortionCorrectionMode: Pts03EnumValue?,
    val distortionCorrectionModeAvailability: Pts03KeyAvailability,
    val lensFocalLengthMm: Float?,
    val lensIntrinsicCalibration: List<Float>?,
    val lensFocusDistanceDiopters: Float?,
    val lensState: Pts03EnumValue?,
    val controlAfMode: Pts03EnumValue?,
    val controlAfState: Pts03EnumValue?,
    val scalerCropRegion: Pts03IntRect?,
    val controlZoomRatio: Float?,
    val controlZoomRatioAvailability: Pts03KeyAvailability,
    val lensOpticalStabilizationMode: Pts03EnumValue?,
    val controlVideoStabilizationMode: Pts03EnumValue?,
    val hotPixelMode: Pts03EnumValue?,
    val noiseReductionMode: Pts03EnumValue?,
    val edgeMode: Pts03EnumValue?,
)

/**
 * One `CaptureResult` → one immutable snapshot: SKY-1's [exposure] contract unchanged, plus the
 * PTS-03 [cameraTruth]. [sensorTimestampNanos] is the single join key [SkyExposureJoin] uses.
 */
internal data class SkyCaptureResultSnapshot(
    val exposure: SkyExposureSample,
    val cameraTruth: Pts03CaptureTruth,
) {
    init {
        require(exposure.sensorTimestampNanos == cameraTruth.sensorTimestampNanos) {
            "exposure and cameraTruth must come from the same CaptureResult; timestamps " +
                "${exposure.sensorTimestampNanos} != ${cameraTruth.sensorTimestampNanos}"
        }
    }

    val sensorTimestampNanos: Long? get() = exposure.sensorTimestampNanos
}

/**
 * Builds the PTS-03 half of one result. Pure: [reader] supplies plain values and [sdkInt] decides which
 * API-gated keys may be read at all (a gated key is never even requested from [reader] below its level).
 */
internal fun pts03CaptureTruthOf(
    reader: Pts03CaptureResultReader,
    sdkInt: Int,
): Pts03CaptureTruth {
    fun available(field: Pts03CaptureResultField): Boolean = sdkInt >= field.minSdk

    fun raw(field: Pts03CaptureResultField): Any? =
        if (!available(field)) {
            null
        } else {
            try {
                reader.read(field)
            } catch (_: RuntimeException) {
                // A reader must not throw, but an OEM key read that does must cost one field, not the experiment.
                null
            }
        }

    fun availability(
        field: Pts03CaptureResultField,
        value: Any?,
    ): Pts03KeyAvailability =
        when {
            !available(field) -> Pts03KeyAvailability.API_UNSUPPORTED
            value == null -> Pts03KeyAvailability.NOT_REPORTED
            else -> Pts03KeyAvailability.REPORTED
        }

    fun enumOf(
        field: Pts03CaptureResultField,
        names: Map<Int, String>,
    ): Pts03EnumValue? = (raw(field) as? Int)?.let { pts03EnumValue(it, names) }

    val physicalId = (raw(Pts03CaptureResultField.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) as? String)
    val distortionMode = enumOf(Pts03CaptureResultField.DISTORTION_CORRECTION_MODE, PTS03_DISTORTION_MODE_NAMES)
    val zoomRatio = raw(Pts03CaptureResultField.CONTROL_ZOOM_RATIO) as? Float

    return Pts03CaptureTruth(
        sensorTimestampNanos = raw(Pts03CaptureResultField.SENSOR_TIMESTAMP) as? Long,
        activePhysicalCameraId = physicalId,
        activePhysicalCameraIdAvailability =
            availability(Pts03CaptureResultField.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID, physicalId),
        distortionCorrectionMode = distortionMode,
        distortionCorrectionModeAvailability =
            availability(Pts03CaptureResultField.DISTORTION_CORRECTION_MODE, distortionMode),
        lensFocalLengthMm = raw(Pts03CaptureResultField.LENS_FOCAL_LENGTH) as? Float,
        lensIntrinsicCalibration = (raw(Pts03CaptureResultField.LENS_INTRINSIC_CALIBRATION) as? FloatArray)?.toList(),
        lensFocusDistanceDiopters = raw(Pts03CaptureResultField.LENS_FOCUS_DISTANCE) as? Float,
        lensState = enumOf(Pts03CaptureResultField.LENS_STATE, PTS03_LENS_STATE_NAMES),
        controlAfMode = enumOf(Pts03CaptureResultField.CONTROL_AF_MODE, PTS03_AF_MODE_NAMES),
        controlAfState = enumOf(Pts03CaptureResultField.CONTROL_AF_STATE, PTS03_AF_STATE_NAMES),
        scalerCropRegion = (raw(Pts03CaptureResultField.SCALER_CROP_REGION) as? Pts03IntRect)?.copy(),
        controlZoomRatio = zoomRatio,
        controlZoomRatioAvailability = availability(Pts03CaptureResultField.CONTROL_ZOOM_RATIO, zoomRatio),
        lensOpticalStabilizationMode =
            enumOf(Pts03CaptureResultField.LENS_OPTICAL_STABILIZATION_MODE, PTS03_OIS_MODE_NAMES),
        controlVideoStabilizationMode =
            enumOf(Pts03CaptureResultField.CONTROL_VIDEO_STABILIZATION_MODE, PTS03_VIDEO_STABILIZATION_MODE_NAMES),
        hotPixelMode = enumOf(Pts03CaptureResultField.HOT_PIXEL_MODE, PTS03_HOT_PIXEL_MODE_NAMES),
        noiseReductionMode = enumOf(Pts03CaptureResultField.NOISE_REDUCTION_MODE, PTS03_NOISE_REDUCTION_MODE_NAMES),
        edgeMode = enumOf(Pts03CaptureResultField.EDGE_MODE, PTS03_EDGE_MODE_NAMES),
    )
}

internal fun pts03EnumValue(
    raw: Int,
    names: Map<Int, String>,
): Pts03EnumValue = Pts03EnumValue(raw = raw, name = names[raw] ?: "UNKNOWN_$raw")

// Camera2 constant values, spelled as literals with their constant names so the mapping is readable on
// any compile SDK and immune to lint's InlinedApi warning for API-28+/30+/33+ constants.
internal val PTS03_DISTORTION_MODE_NAMES: Map<Int, String> = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY")
internal val PTS03_LENS_STATE_NAMES: Map<Int, String> = mapOf(0 to "STATIONARY", 1 to "MOVING")
internal val PTS03_AF_MODE_NAMES: Map<Int, String> =
    mapOf(0 to "OFF", 1 to "AUTO", 2 to "MACRO", 3 to "CONTINUOUS_VIDEO", 4 to "CONTINUOUS_PICTURE", 5 to "EDOF")
internal val PTS03_AF_STATE_NAMES: Map<Int, String> =
    mapOf(
        0 to "INACTIVE",
        1 to "PASSIVE_SCAN",
        2 to "PASSIVE_FOCUSED",
        3 to "ACTIVE_SCAN",
        4 to "FOCUSED_LOCKED",
        5 to "NOT_FOCUSED_LOCKED",
        6 to "PASSIVE_UNFOCUSED",
    )
internal val PTS03_OIS_MODE_NAMES: Map<Int, String> = mapOf(0 to "OFF", 1 to "ON")
internal val PTS03_VIDEO_STABILIZATION_MODE_NAMES: Map<Int, String> =
    mapOf(0 to "OFF", 1 to "ON", 2 to "PREVIEW_STABILIZATION")
internal val PTS03_HOT_PIXEL_MODE_NAMES: Map<Int, String> = mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY")
internal val PTS03_NOISE_REDUCTION_MODE_NAMES: Map<Int, String> =
    mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "MINIMAL", 4 to "ZERO_SHUTTER_LAG")
internal val PTS03_EDGE_MODE_NAMES: Map<Int, String> =
    mapOf(0 to "OFF", 1 to "FAST", 2 to "HIGH_QUALITY", 3 to "ZERO_SHUTTER_LAG")

/**
 * The Android-facing [Pts03CaptureResultReader]. Each API-gated key is read only behind an explicit
 * `SDK_INT` check, so an older device never touches a `CaptureResult.Key` field that does not exist on
 * it. Any exception from an OEM key read becomes `null` for that one field.
 */
internal class Camera2CaptureResultReader(
    private val result: CaptureResult,
) : Pts03CaptureResultReader {
    override fun read(field: Pts03CaptureResultField): Any? =
        try {
            readUnchecked(field)
        } catch (_: RuntimeException) {
            null
        }

    private fun readUnchecked(field: Pts03CaptureResultField): Any? =
        when (field) {
            Pts03CaptureResultField.SENSOR_TIMESTAMP -> result.get(CaptureResult.SENSOR_TIMESTAMP)
            Pts03CaptureResultField.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                } else {
                    null
                }
            Pts03CaptureResultField.DISTORTION_CORRECTION_MODE ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    result.get(CaptureResult.DISTORTION_CORRECTION_MODE)
                } else {
                    null
                }
            Pts03CaptureResultField.LENS_FOCAL_LENGTH -> result.get(CaptureResult.LENS_FOCAL_LENGTH)
            Pts03CaptureResultField.LENS_INTRINSIC_CALIBRATION -> result.get(CaptureResult.LENS_INTRINSIC_CALIBRATION)
            Pts03CaptureResultField.LENS_FOCUS_DISTANCE -> result.get(CaptureResult.LENS_FOCUS_DISTANCE)
            Pts03CaptureResultField.LENS_STATE -> result.get(CaptureResult.LENS_STATE)
            Pts03CaptureResultField.CONTROL_AF_MODE -> result.get(CaptureResult.CONTROL_AF_MODE)
            Pts03CaptureResultField.CONTROL_AF_STATE -> result.get(CaptureResult.CONTROL_AF_STATE)
            Pts03CaptureResultField.SCALER_CROP_REGION -> result.get(CaptureResult.SCALER_CROP_REGION)?.toPts03IntRect()
            Pts03CaptureResultField.CONTROL_ZOOM_RATIO ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    result.get(CaptureResult.CONTROL_ZOOM_RATIO)
                } else {
                    null
                }
            Pts03CaptureResultField.LENS_OPTICAL_STABILIZATION_MODE ->
                result.get(CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
            Pts03CaptureResultField.CONTROL_VIDEO_STABILIZATION_MODE ->
                result.get(CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
            Pts03CaptureResultField.HOT_PIXEL_MODE -> result.get(CaptureResult.HOT_PIXEL_MODE)
            Pts03CaptureResultField.NOISE_REDUCTION_MODE -> result.get(CaptureResult.NOISE_REDUCTION_MODE)
            Pts03CaptureResultField.EDGE_MODE -> result.get(CaptureResult.EDGE_MODE)
        }
}

internal fun Rect.toPts03IntRect(): Pts03IntRect = Pts03IntRect(left, top, right, bottom)

/**
 * The one entry point the camera callback uses: both halves are read from the same [result] object in
 * one call, so they can never describe two different frames.
 */
internal fun skyCaptureResultSnapshotOf(result: CaptureResult): SkyCaptureResultSnapshot {
    val exposure = skyExposureSampleOf(result)
    val truth = pts03CaptureTruthOf(Camera2CaptureResultReader(result), Build.VERSION.SDK_INT)
    // Both halves read SENSOR_TIMESTAMP from the same immutable result, so they agree; the copy below
    // only guards a pathological reader failure on one side from tripping the snapshot's invariant.
    return SkyCaptureResultSnapshot(exposure, truth.copy(sensorTimestampNanos = exposure.sensorTimestampNanos))
}
