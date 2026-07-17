package dev.pointtosky.mobile.ar.camera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo

/**
 * CAM-2c topology recon (`internalDebug` only, task §3): enumerates every rear Camera2 camera via the
 * raw platform `CameraManager` - deliberately not via `ProcessCameraProvider.availableCameraInfos`,
 * since CameraX only exposes cameras it considers bindable, and this recon wants the *complete*
 * declared topology (task: "For every relevant rear logical/physical camera"). This mirrors
 * `Camera2CharacteristicsSource`'s own existing, narrow, read-only `CameraManager` diagnostic bypass
 * (used there for `physicalCameraIds` only) - never used to bind or select a camera, purely
 * informational.
 *
 * [boundCameraInfo], when supplied (the currently bound `CameraInfo` from `CameraPreview`'s
 * `onCameraInfo` callback), is used only to label which entry CameraX actually bound as the primary
 * rear camera - never to *infer* which physical ID is "the main/wide" one (task explicitly forbids
 * inferring that from ID ordering).
 */
@OptIn(ExperimentalCamera2Interop::class)
internal fun buildCameraTopologyReport(
    context: Context,
    boundCameraInfo: CameraInfo?,
): CameraTopologyReport {
    val cameraManager = context.getSystemService(CameraManager::class.java)
    val boundCamera2Id = boundCameraInfo?.let { runCatching { Camera2CameraInfo.from(it).cameraId }.getOrNull() }
    if (cameraManager == null) {
        return CameraTopologyReport(entries = emptyList(), boundPrimaryRearCamera2Id = boundCamera2Id)
    }
    val entries =
        runCatching { cameraManager.cameraIdList.toList() }
            .getOrDefault(emptyList())
            .mapNotNull { id -> buildEntryOrNull(cameraManager, id) }
            .filter { it.lensFacing == LENS_FACING_BACK_LABEL }
    return CameraTopologyReport(entries = entries, boundPrimaryRearCamera2Id = boundCamera2Id)
}

private const val LENS_FACING_BACK_LABEL = "BACK"

private fun buildEntryOrNull(
    cameraManager: CameraManager,
    cameraId: String,
): CameraTopologyEntry? {
    val characteristics = runCatching { cameraManager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return null
    val lensFacing =
        when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_BACK -> "BACK"
            CameraCharacteristics.LENS_FACING_FRONT -> "FRONT"
            CameraCharacteristics.LENS_FACING_EXTERNAL -> "EXTERNAL"
            else -> "UNKNOWN"
        }
    val capabilities =
        characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.map { capabilityLabel(it) }
            ?: emptyList()
    val isLogicalMultiCamera = capabilities.contains(CAPABILITY_LOGICAL_MULTI_CAMERA_LABEL)
    val physicalIds =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { characteristics.physicalCameraIds.toList().sorted() }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
    return CameraTopologyEntry(
        camera2Id = cameraId,
        lensFacing = lensFacing,
        isLogicalMultiCamera = isLogicalMultiCamera,
        declaredPhysicalCameraIds = physicalIds,
        focalLengthsMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList(),
        sensorPhysicalWidthMm = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width,
        sensorPhysicalHeightMm = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.height,
        pixelArrayWidthPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.width,
        pixelArrayHeightPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.height,
        activeArrayLeftPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.left,
        activeArrayTopPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.top,
        activeArrayRightPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.right,
        activeArrayBottomPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.bottom,
        preCorrectionActiveArrayLeftPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.left,
        preCorrectionActiveArrayTopPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.top,
        preCorrectionActiveArrayRightPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.right,
        preCorrectionActiveArrayBottomPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)?.bottom,
        hardwareLevel = hardwareLevelLabel(characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)),
        capabilities = capabilities,
        imageAnalysisStreamConfigurationsPx = imageAnalysisStreamConfigurations(characteristics),
        physicalCameraCandidates = physicalIds.mapNotNull { buildPhysicalEntryOrNull(cameraManager, it) },
    )
}

private fun buildPhysicalEntryOrNull(
    cameraManager: CameraManager,
    physicalCameraId: String,
): PhysicalCameraTopologyEntry? {
    val characteristics = runCatching { cameraManager.getCameraCharacteristics(physicalCameraId) }.getOrNull() ?: return null
    return PhysicalCameraTopologyEntry(
        camera2Id = physicalCameraId,
        focalLengthsMm = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.toList() ?: emptyList(),
        sensorPhysicalWidthMm = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.width,
        sensorPhysicalHeightMm = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)?.height,
        pixelArrayWidthPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.width,
        pixelArrayHeightPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)?.height,
        activeArrayLeftPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.left,
        activeArrayTopPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.top,
        activeArrayRightPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.right,
        activeArrayBottomPx = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.bottom,
    )
}

private const val CAPABILITY_LOGICAL_MULTI_CAMERA_LABEL = "LOGICAL_MULTI_CAMERA"

private fun capabilityLabel(capability: Int): String =
    when (capability) {
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE -> "BACKWARD_COMPATIBLE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR -> "MANUAL_SENSOR"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING -> "MANUAL_POST_PROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW -> "RAW"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_READ_SENSOR_SETTINGS -> "READ_SENSOR_SETTINGS"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE -> "BURST_CAPTURE"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_YUV_REPROCESSING -> "YUV_REPROCESSING"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT -> "DEPTH_OUTPUT"
        CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_CONSTRAINED_HIGH_SPEED_VIDEO -> "CONSTRAINED_HIGH_SPEED_VIDEO"
        29 -> CAPABILITY_LOGICAL_MULTI_CAMERA_LABEL // REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA (API 28+; avoid @RequiresApi field ref pre-28)
        else -> "CAPABILITY_$capability"
    }

private fun hardwareLevelLabel(level: Int?): String? =
    when (level) {
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
        CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
        null -> null
        else -> "UNKNOWN_$level"
    }

/** `ImageAnalysis` binds `ImageFormat.YUV_420_888` output by default (CameraX's own default analysis
 * format) - lists every such output size the camera declares, relevant to picking an `ImageAnalysis`
 * target resolution (task §3's "available stream configurations relevant to ImageAnalysis"). */
private fun imageAnalysisStreamConfigurations(characteristics: CameraCharacteristics): List<String> {
    val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
    val sizes = runCatching { map.getOutputSizes(ImageFormat.YUV_420_888) }.getOrNull() ?: return emptyList()
    return sizes.map { "${it.width}x${it.height}" }
}
