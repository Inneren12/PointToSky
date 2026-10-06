package dev.pointtosky.mobile.ar.camera

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build

/**
 * PTS-03 (`internalDebug`-only): a deterministic static-characteristics record for the opened logical
 * camera **and every physical child it declares**.
 *
 * Fields that belong to the existing calibration model are carried in the production
 * [CameraCharacteristicsSnapshot] (reused, not duplicated). Fields that only PTS-03 evidence needs —
 * available distortion modes, `LENS_POSE_*`, orientation/facing/capabilities/hardware level/timestamp
 * source — stay in this internalDebug type so the production contract is not widened for an experiment.
 *
 * Child IDs are discovered from the logical camera's own `getPhysicalCameraIds()`; nothing here knows
 * that a Pixel 9 happens to declare `2`, `3`, `4`.
 *
 * As with [Pts03CaptureResultTruth], reads go through a field enum and a reader so the pure builder is
 * unit-testable without `CameraCharacteristics.Key` instances (which are `null` under the JVM stub).
 */

/** Every static key PTS-03 reads, with the API level at which Camera2 defines it. */
internal enum class Pts03CharacteristicsField(
    val minSdk: Int,
) {
    LENS_INFO_AVAILABLE_FOCAL_LENGTHS(21),
    SENSOR_INFO_PHYSICAL_SIZE(21),
    SENSOR_INFO_PIXEL_ARRAY_SIZE(21),
    SENSOR_INFO_ACTIVE_ARRAY_SIZE(21),
    SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE(23),
    LENS_INTRINSIC_CALIBRATION(23),
    LENS_DISTORTION(28),
    DISTORTION_CORRECTION_AVAILABLE_MODES(28),
    LENS_POSE_ROTATION(23),
    LENS_POSE_TRANSLATION(23),
    LENS_POSE_REFERENCE(28),
    SENSOR_ORIENTATION(21),
    LENS_FACING(21),
    REQUEST_AVAILABLE_CAPABILITIES(21),
    INFO_SUPPORTED_HARDWARE_LEVEL(21),
    SENSOR_INFO_TIMESTAMP_SOURCE(21),

    /** `CameraCharacteristics.getPhysicalCameraIds()` (a method, not a key; API 28). */
    PHYSICAL_CAMERA_IDS(28),
}

/** An immutable width/height pair (`Size` / `SizeF` copy). */
internal data class Pts03SizeF(
    val width: Float,
    val height: Float,
)

/**
 * Reads one static field as a plain JVM value: `FloatArray`, `IntArray`, `Int`, `Set<String>`,
 * [Pts03IntRect] or [Pts03SizeF]. `null` when absent. Must not throw.
 */
internal fun interface Pts03CharacteristicsReader {
    fun read(field: Pts03CharacteristicsField): Any?
}

/** Whether this record describes the opened logical camera or one of its declared physical children. */
internal enum class Pts03CameraRole {
    OPENED_LOGICAL,
    DECLARED_PHYSICAL_CHILD,
}

/**
 * @property snapshot the production calibration snapshot, populated from the same reads.
 * @property lensPoseRotationXyzw raw `LENS_POSE_ROTATION`, Camera2 order `[x, y, z, w]`. Parse only through
 *   [parseCamera2LensPoseRotation].
 * @property lensPoseTranslationMeters raw `LENS_POSE_TRANSLATION`. Recorded only: irrelevant for stars at infinity.
 * @property readFailure non-null when the characteristics could not be read at all for [cameraId].
 */
internal data class Pts03CameraStaticCharacteristics(
    val cameraId: String,
    val role: Pts03CameraRole,
    val snapshot: CameraCharacteristicsSnapshot?,
    val distortionCorrectionAvailableModes: List<Pts03EnumValue>?,
    val lensPoseRotationXyzw: List<Float>?,
    val lensPoseTranslationMeters: List<Float>?,
    val lensPoseReference: Pts03EnumValue?,
    val sensorOrientationDegrees: Int?,
    val lensFacing: Pts03EnumValue?,
    val availableCapabilities: List<Pts03EnumValue>?,
    val supportedHardwareLevel: Pts03EnumValue?,
    val timestampSource: Pts03EnumValue?,
    val readFailure: String? = null,
)

/** The opened logical camera plus every child it declares, children in sorted ID order. */
internal data class Pts03CameraCharacteristicsSet(
    val logicalCameraId: String,
    val logical: Pts03CameraStaticCharacteristics,
    val physicalChildren: List<Pts03CameraStaticCharacteristics>,
) {
    /** `null` when the logical camera's declared children could not be read; empty for a non-logical camera. */
    val declaredPhysicalCameraIds: List<String>? get() = logical.snapshot?.physicalCameraIds?.sorted()

    fun forCameraId(cameraId: String?): Pts03CameraStaticCharacteristics? =
        when (cameraId) {
            null -> null
            logicalCameraId -> logical
            else -> physicalChildren.firstOrNull { it.cameraId == cameraId }
        }
}

/** Opens a reader for one camera ID, or returns `null` (never throws) when it cannot be read. */
internal fun interface Pts03CharacteristicsProvider {
    fun readerFor(cameraId: String): Pts03CharacteristicsReader?
}

/** Pure: builds one static record from [reader]. API-gated keys are never requested below their level. */
internal fun pts03StaticCharacteristicsOf(
    cameraId: String,
    role: Pts03CameraRole,
    reader: Pts03CharacteristicsReader,
    sdkInt: Int,
): Pts03CameraStaticCharacteristics {
    fun raw(field: Pts03CharacteristicsField): Any? =
        if (sdkInt < field.minSdk) {
            null
        } else {
            try {
                reader.read(field)
            } catch (_: RuntimeException) {
                null
            }
        }

    val activeArray = raw(Pts03CharacteristicsField.SENSOR_INFO_ACTIVE_ARRAY_SIZE) as? Pts03IntRect
    val preCorrection = raw(Pts03CharacteristicsField.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE) as? Pts03IntRect
    val pixelArray = raw(Pts03CharacteristicsField.SENSOR_INFO_PIXEL_ARRAY_SIZE) as? Pts03SizeF
    val physicalSize = raw(Pts03CharacteristicsField.SENSOR_INFO_PHYSICAL_SIZE) as? Pts03SizeF
    val capabilities = raw(Pts03CharacteristicsField.REQUEST_AVAILABLE_CAPABILITIES) as? IntArray

    @Suppress("UNCHECKED_CAST")
    val physicalIds =
        (
            raw(
                Pts03CharacteristicsField.PHYSICAL_CAMERA_IDS,
            ) as? Set<*>
        )?.filterIsInstance<String>()?.toSet()

    val snapshot =
        CameraCharacteristicsSnapshot(
            availableFocalLengthsMm =
                (
                    raw(
                        Pts03CharacteristicsField.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                    ) as? FloatArray
                )?.copyOf(),
            sensorPhysicalWidthMm = physicalSize?.width,
            sensorPhysicalHeightMm = physicalSize?.height,
            activeArrayLeftPx = activeArray?.left,
            activeArrayTopPx = activeArray?.top,
            activeArrayRightPx = activeArray?.right,
            activeArrayBottomPx = activeArray?.bottom,
            pixelArrayWidthPx = pixelArray?.width?.toInt(),
            pixelArrayHeightPx = pixelArray?.height?.toInt(),
            preCorrectionActiveArrayLeftPx = preCorrection?.left,
            preCorrectionActiveArrayTopPx = preCorrection?.top,
            preCorrectionActiveArrayRightPx = preCorrection?.right,
            preCorrectionActiveArrayBottomPx = preCorrection?.bottom,
            lensIntrinsicCalibration =
                (
                    raw(
                        Pts03CharacteristicsField.LENS_INTRINSIC_CALIBRATION,
                    ) as? FloatArray
                )?.copyOf(),
            lensDistortion = (raw(Pts03CharacteristicsField.LENS_DISTORTION) as? FloatArray)?.copyOf(),
            isLogicalMultiCamera = capabilities?.contains(PTS03_CAPABILITY_LOGICAL_MULTI_CAMERA) == true,
            cameraId = cameraId,
            physicalCameraIds = physicalIds,
        )

    fun enumOf(
        field: Pts03CharacteristicsField,
        names: Map<Int, String>,
    ): Pts03EnumValue? = (raw(field) as? Int)?.let { pts03EnumValue(it, names) }

    return Pts03CameraStaticCharacteristics(
        cameraId = cameraId,
        role = role,
        snapshot = snapshot,
        distortionCorrectionAvailableModes =
            (raw(Pts03CharacteristicsField.DISTORTION_CORRECTION_AVAILABLE_MODES) as? IntArray)
                ?.map { pts03EnumValue(it, PTS03_DISTORTION_MODE_NAMES) },
        lensPoseRotationXyzw = (raw(Pts03CharacteristicsField.LENS_POSE_ROTATION) as? FloatArray)?.toList(),
        lensPoseTranslationMeters = (raw(Pts03CharacteristicsField.LENS_POSE_TRANSLATION) as? FloatArray)?.toList(),
        lensPoseReference = enumOf(Pts03CharacteristicsField.LENS_POSE_REFERENCE, PTS03_LENS_POSE_REFERENCE_NAMES),
        sensorOrientationDegrees = raw(Pts03CharacteristicsField.SENSOR_ORIENTATION) as? Int,
        lensFacing = enumOf(Pts03CharacteristicsField.LENS_FACING, PTS03_LENS_FACING_NAMES),
        availableCapabilities = capabilities?.map { pts03EnumValue(it, PTS03_CAPABILITY_NAMES) },
        supportedHardwareLevel =
            enumOf(
                Pts03CharacteristicsField.INFO_SUPPORTED_HARDWARE_LEVEL,
                PTS03_HARDWARE_LEVEL_NAMES,
            ),
        timestampSource =
            enumOf(
                Pts03CharacteristicsField.SENSOR_INFO_TIMESTAMP_SOURCE,
                PTS03_TIMESTAMP_SOURCE_NAMES,
            ),
    )
}

/**
 * Pure: the logical camera's record plus one record per declared child (sorted). A child whose
 * characteristics cannot be read is still listed, with [Pts03CameraStaticCharacteristics.readFailure]
 * set, so a missing child is visible in the evidence rather than silently omitted.
 */
internal fun buildPts03CameraCharacteristicsSet(
    logicalCameraId: String,
    provider: Pts03CharacteristicsProvider,
    sdkInt: Int,
): Pts03CameraCharacteristicsSet {
    val logical =
        provider.readerFor(logicalCameraId)?.let {
            pts03StaticCharacteristicsOf(logicalCameraId, Pts03CameraRole.OPENED_LOGICAL, it, sdkInt)
        } ?: unreadable(logicalCameraId, Pts03CameraRole.OPENED_LOGICAL)
    val children =
        logical.snapshot?.physicalCameraIds.orEmpty().sorted().map { childId ->
            provider.readerFor(childId)?.let {
                pts03StaticCharacteristicsOf(childId, Pts03CameraRole.DECLARED_PHYSICAL_CHILD, it, sdkInt)
            } ?: unreadable(childId, Pts03CameraRole.DECLARED_PHYSICAL_CHILD)
        }
    return Pts03CameraCharacteristicsSet(logicalCameraId, logical, children)
}

private fun unreadable(
    cameraId: String,
    role: Pts03CameraRole,
): Pts03CameraStaticCharacteristics =
    Pts03CameraStaticCharacteristics(
        cameraId = cameraId,
        role = role,
        snapshot = null,
        distortionCorrectionAvailableModes = null,
        lensPoseRotationXyzw = null,
        lensPoseTranslationMeters = null,
        lensPoseReference = null,
        sensorOrientationDegrees = null,
        lensFacing = null,
        availableCapabilities = null,
        supportedHardwareLevel = null,
        timestampSource = null,
        readFailure = "characteristics unavailable for cameraId=$cameraId",
    )

internal const val PTS03_CAPABILITY_LOGICAL_MULTI_CAMERA: Int = 11

internal val PTS03_LENS_POSE_REFERENCE_NAMES: Map<Int, String> =
    mapOf(0 to "PRIMARY_CAMERA", 1 to "GYROSCOPE", 2 to "UNDEFINED", 3 to "AUTOMOTIVE")
internal val PTS03_LENS_FACING_NAMES: Map<Int, String> = mapOf(0 to "FRONT", 1 to "BACK", 2 to "EXTERNAL")
internal val PTS03_HARDWARE_LEVEL_NAMES: Map<Int, String> =
    mapOf(0 to "LIMITED", 1 to "FULL", 2 to "LEGACY", 3 to "LEVEL_3", 4 to "EXTERNAL")
internal val PTS03_TIMESTAMP_SOURCE_NAMES: Map<Int, String> = mapOf(0 to "UNKNOWN", 1 to "REALTIME")
internal val PTS03_CAPABILITY_NAMES: Map<Int, String> =
    mapOf(
        0 to "BACKWARD_COMPATIBLE",
        1 to "MANUAL_SENSOR",
        2 to "MANUAL_POST_PROCESSING",
        3 to "RAW",
        4 to "PRIVATE_REPROCESSING",
        5 to "READ_SENSOR_SETTINGS",
        6 to "BURST_CAPTURE",
        7 to "YUV_REPROCESSING",
        8 to "DEPTH_OUTPUT",
        9 to "CONSTRAINED_HIGH_SPEED_VIDEO",
        10 to "MOTION_TRACKING",
        PTS03_CAPABILITY_LOGICAL_MULTI_CAMERA to "LOGICAL_MULTI_CAMERA",
        12 to "MONOCHROME",
        13 to "SECURE_IMAGE_DATA",
        14 to "SYSTEM_CAMERA",
        15 to "OFFLINE_PROCESSING",
        16 to "ULTRA_HIGH_RESOLUTION_SENSOR",
        17 to "REMOSAIC_REPROCESSING",
        18 to "DYNAMIC_RANGE_TEN_BIT",
        19 to "STREAM_USE_CASE",
        20 to "COLOR_SPACE_PROFILES",
    )

/** Android-facing reader over one `CameraCharacteristics`; every API-gated key behind an explicit `SDK_INT` check. */
internal class Camera2CharacteristicsReader(
    private val characteristics: CameraCharacteristics,
) : Pts03CharacteristicsReader {
    override fun read(field: Pts03CharacteristicsField): Any? =
        try {
            readUnchecked(field)
        } catch (_: RuntimeException) {
            null
        }

    private fun readUnchecked(field: Pts03CharacteristicsField): Any? {
        val c = characteristics
        return when (field) {
            Pts03CharacteristicsField.LENS_INFO_AVAILABLE_FOCAL_LENGTHS ->
                c.get(
                    CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
                )
            Pts03CharacteristicsField.SENSOR_INFO_PHYSICAL_SIZE ->
                c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.let { Pts03SizeF(it.width, it.height) }
            Pts03CharacteristicsField.SENSOR_INFO_PIXEL_ARRAY_SIZE ->
                c
                    .get(
                        CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE,
                    )?.let { Pts03SizeF(it.width.toFloat(), it.height.toFloat()) }
            Pts03CharacteristicsField.SENSOR_INFO_ACTIVE_ARRAY_SIZE ->
                c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.toPts03IntRect()
            Pts03CharacteristicsField.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE ->
                c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.toPts03IntRect()
            Pts03CharacteristicsField.LENS_INTRINSIC_CALIBRATION ->
                c.get(
                    CameraCharacteristics.LENS_INTRINSIC_CALIBRATION,
                )
            Pts03CharacteristicsField.LENS_DISTORTION ->
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.P
                ) {
                    c.get(CameraCharacteristics.LENS_DISTORTION)
                } else {
                    null
                }
            Pts03CharacteristicsField.DISTORTION_CORRECTION_AVAILABLE_MODES ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES)
                } else {
                    null
                }
            Pts03CharacteristicsField.LENS_POSE_ROTATION -> c.get(CameraCharacteristics.LENS_POSE_ROTATION)
            Pts03CharacteristicsField.LENS_POSE_TRANSLATION -> c.get(CameraCharacteristics.LENS_POSE_TRANSLATION)
            Pts03CharacteristicsField.LENS_POSE_REFERENCE ->
                if (Build.VERSION.SDK_INT >=
                    Build.VERSION_CODES.P
                ) {
                    c.get(CameraCharacteristics.LENS_POSE_REFERENCE)
                } else {
                    null
                }
            Pts03CharacteristicsField.SENSOR_ORIENTATION -> c.get(CameraCharacteristics.SENSOR_ORIENTATION)
            Pts03CharacteristicsField.LENS_FACING -> c.get(CameraCharacteristics.LENS_FACING)
            Pts03CharacteristicsField.REQUEST_AVAILABLE_CAPABILITIES ->
                c.get(
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES,
                )
            Pts03CharacteristicsField.INFO_SUPPORTED_HARDWARE_LEVEL ->
                c.get(
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL,
                )
            Pts03CharacteristicsField.SENSOR_INFO_TIMESTAMP_SOURCE ->
                c.get(
                    CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE,
                )
            Pts03CharacteristicsField.PHYSICAL_CAMERA_IDS ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) c.physicalCameraIds.toSet() else null
        }
    }
}

/**
 * Reads the logical camera and every declared child through the platform `CameraManager` (read-only;
 * never binds or opens a camera). Physical children are readable through `getCameraCharacteristics`
 * on API 28+, which is also when `getPhysicalCameraIds()` exists.
 */
internal fun capturePts03CameraCharacteristicsSet(
    context: Context,
    logicalCameraId: String,
): Pts03CameraCharacteristicsSet {
    val cameraManager = context.getSystemService(CameraManager::class.java)
    val provider =
        Pts03CharacteristicsProvider { cameraId ->
            try {
                cameraManager?.getCameraCharacteristics(cameraId)?.let { Camera2CharacteristicsReader(it) }
            } catch (_: Exception) {
                null
            }
        }
    return buildPts03CameraCharacteristicsSet(logicalCameraId, provider, Build.VERSION.SDK_INT)
}
