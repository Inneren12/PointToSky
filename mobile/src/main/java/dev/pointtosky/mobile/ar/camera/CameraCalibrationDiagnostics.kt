package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.ActiveArrayIntrinsics
import dev.pointtosky.core.astro.projection.camera.ActiveArraySensorCropRegion
import dev.pointtosky.core.astro.projection.camera.AnalysisBufferIntrinsicsValues
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass

/**
 * Bounded, debug-only snapshot of one successful [resolveAnalysisBufferIntrinsics] mapping (CAM-2c
 * §9) — every intermediate quantity the calibrated pipeline actually computed, for the internal
 * diagnostics panel. Never persisted externally (see [CameraCalibrationDiagnosticsSource]'s KDoc);
 * this device's exact sensor/focal-length/crop numbers are shown on-screen only, in an
 * `internalDebug`-gated build.
 *
 * @property activeArrayWidthPx `SENSOR_INFO_ACTIVE_ARRAY_SIZE` width, pixels.
 * @property activeArrayHeightPx `SENSOR_INFO_ACTIVE_ARRAY_SIZE` height, pixels.
 * @property sensorWidthMm physical sensor width, millimetres.
 * @property sensorHeightMm physical sensor height, millimetres.
 * @property focalLengthMm the single resolved `LENS_INFO_AVAILABLE_FOCAL_LENGTHS` value, millimetres.
 * @property activeFxPx horizontal focal length in active-array pixels.
 * @property activeFyPx vertical focal length in active-array pixels.
 * @property activeCxPx principal point X in active-array pixels.
 * @property activeCyPx principal point Y in active-array pixels.
 * @property cropLeftPx the active-array-space crop region ([ActiveArraySensorCropRegion]) left edge.
 * @property cropTopPx the crop region top edge.
 * @property cropRightPx the crop region right edge.
 * @property cropBottomPx the crop region bottom edge.
 * @property bufferFxPx horizontal focal length in the exact analysis buffer's pixels.
 * @property bufferFyPx vertical focal length in the exact analysis buffer's pixels.
 * @property bufferCxPx principal point X in the exact analysis buffer's pixels.
 * @property bufferCyPx principal point Y in the exact analysis buffer's pixels.
 * @property quality [CameraIntrinsicsQuality] this mapping resolved.
 * @property sensorToBufferMappingSource where the sensor-to-buffer transform came from — always
 *   [SENSOR_TO_BUFFER_MAPPING_SOURCE] for a real [resolveAnalysisBufferIntrinsics] success, since
 *   that is the only path this snapshot is ever built from; carried as an explicit string (not
 *   derived from context at the display layer) so the panel never has to re-guess it.
 * @property transformClass (CAM-2c fix §1) how the real sensor-to-buffer matrix related active-array
 *   axes to this buffer's own axes — see
 *   `dev.pointtosky.core.astro.projection.camera.classifySensorToBufferMatrix`. Always one of the
 *   four *supported* classes here, since this snapshot is only ever built from a successful mapping.
 * @property skewDiagnosticReason (CAM-2c fix §3) non-`null` only when `LENS_INTRINSIC_CALIBRATION`
 *   was otherwise structurally usable but its skew term exceeded [INTRINSIC_SKEW_TOLERANCE_PX], so
 *   [quality] fell back to [CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT] despite calibration
 *   metadata being present — see [CameraCalibrationDiagnosticReason.NON_ZERO_INTRINSIC_SKEW].
 * @property cameraId (CAM-2c fix §5) the bound Camera2 camera ID this resolution described.
 * @property isLogicalMultiCamera (CAM-2c fix §5) always `false` here — a `true` logical-multi-camera
 *   snapshot never reaches a successful [resolveAnalysisBufferIntrinsics] mapping in the first place
 *   (see [AnalysisBufferIntrinsicsResolution.UnsupportedLogicalMultiCameraMapping]); carried anyway so
 *   the diagnostics panel's rendering code has one field to check rather than assuming.
 * @property physicalCameraIds (CAM-2c fix §5, "Option C") this camera's declared physical camera IDs,
 *   when the read-only `CameraManager` diagnostic lookup succeeded — see
 *   [CameraCharacteristicsSnapshot.physicalCameraIds]'s KDoc.
 */
data class CameraCalibrationDiagnostics(
    val activeArrayWidthPx: Int,
    val activeArrayHeightPx: Int,
    val sensorWidthMm: Double,
    val sensorHeightMm: Double,
    val focalLengthMm: Double,
    val activeFxPx: Double,
    val activeFyPx: Double,
    val activeCxPx: Double,
    val activeCyPx: Double,
    val cropLeftPx: Double,
    val cropTopPx: Double,
    val cropRightPx: Double,
    val cropBottomPx: Double,
    val bufferFxPx: Double,
    val bufferFyPx: Double,
    val bufferCxPx: Double,
    val bufferCyPx: Double,
    val quality: CameraIntrinsicsQuality,
    val sensorToBufferMappingSource: String,
    val transformClass: SensorToBufferTransformClass,
    val skewDiagnosticReason: String? = null,
    val cameraId: String? = null,
    val isLogicalMultiCamera: Boolean = false,
    val physicalCameraIds: Set<String>? = null,
) {
    internal companion object {
        /** The one real source this snapshot's crop/mapping numbers ever come from (CAM-2c §5). */
        const val SENSOR_TO_BUFFER_MAPPING_SOURCE = "ImageInfo.getSensorToBufferTransformMatrix"

        fun of(
            active: ActiveArrayIntrinsics,
            cropRegion: ActiveArraySensorCropRegion,
            bufferValues: AnalysisBufferIntrinsicsValues,
            sensorWidthMm: Double,
            sensorHeightMm: Double,
            focalLengthMm: Double,
            quality: CameraIntrinsicsQuality,
            transformClass: SensorToBufferTransformClass,
            skewDiagnosticReason: String? = null,
            cameraId: String? = null,
            isLogicalMultiCamera: Boolean = false,
            physicalCameraIds: Set<String>? = null,
        ) = CameraCalibrationDiagnostics(
            activeArrayWidthPx = active.widthPx,
            activeArrayHeightPx = active.heightPx,
            sensorWidthMm = sensorWidthMm,
            sensorHeightMm = sensorHeightMm,
            focalLengthMm = focalLengthMm,
            activeFxPx = active.fxPx,
            activeFyPx = active.fyPx,
            activeCxPx = active.cxPx,
            activeCyPx = active.cyPx,
            cropLeftPx = cropRegion.leftPx,
            cropTopPx = cropRegion.topPx,
            cropRightPx = cropRegion.rightPx,
            cropBottomPx = cropRegion.bottomPx,
            bufferFxPx = bufferValues.fxPx,
            bufferFyPx = bufferValues.fyPx,
            bufferCxPx = bufferValues.cxPx,
            bufferCyPx = bufferValues.cyPx,
            quality = quality,
            sensorToBufferMappingSource = SENSOR_TO_BUFFER_MAPPING_SOURCE,
            transformClass = transformClass,
            skewDiagnosticReason = skewDiagnosticReason,
            cameraId = cameraId,
            isLogicalMultiCamera = isLogicalMultiCamera,
            physicalCameraIds = physicalCameraIds,
        )
    }
}
