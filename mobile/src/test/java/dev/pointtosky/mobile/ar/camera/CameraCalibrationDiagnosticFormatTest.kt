package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import kotlin.test.Test
import kotlin.test.assertTrue

/** Pure JVM tests for [buildCameraCalibrationDiagnosticText] (CAM-2c §9, fix §1/§3/§5). */
class CameraCalibrationDiagnosticFormatTest {
    private val diagnostics =
        CameraCalibrationDiagnostics(
            activeArrayWidthPx = 4032,
            activeArrayHeightPx = 3024,
            sensorWidthMm = 6.4,
            sensorHeightMm = 4.8,
            focalLengthMm = 3.6,
            activeFxPx = 2268.0,
            activeFyPx = 2268.0,
            activeCxPx = 2016.0,
            activeCyPx = 1512.0,
            cropLeftPx = 0.0,
            cropTopPx = 0.0,
            cropRightPx = 4032.0,
            cropBottomPx = 3024.0,
            bufferFxPx = 360.0,
            bufferFyPx = 360.0,
            bufferCxPx = 320.0,
            bufferCyPx = 240.0,
            quality = CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT,
            sensorToBufferMappingSource = CameraCalibrationDiagnostics.SENSOR_TO_BUFFER_MAPPING_SOURCE,
            transformClass = SensorToBufferTransformClass.AXIS_ALIGNED_0,
            cameraId = "0",
            isLogicalMultiCamera = false,
            physicalCameraIds = null,
        )

    @Test
    fun `text includes every field group`() {
        val text = buildCameraCalibrationDiagnosticText(diagnostics)

        assertTrue(text.contains("4032"))
        assertTrue(text.contains("3024"))
        assertTrue(text.contains("6.40 mm"))
        assertTrue(text.contains("4.80 mm"))
        assertTrue(text.contains("3.60 mm"))
        assertTrue(text.contains("2268.0"))
        assertTrue(text.contains("2016.0"))
        assertTrue(text.contains("1512.0"))
        assertTrue(text.contains("360.0"))
        assertTrue(text.contains("320.0"))
        assertTrue(text.contains("240.0"))
        assertTrue(text.contains("APPROXIMATE_PRINCIPAL_POINT"))
        assertTrue(text.contains(CameraCalibrationDiagnostics.SENSOR_TO_BUFFER_MAPPING_SOURCE))
        assertTrue(text.contains("AXIS_ALIGNED_0"))
        assertTrue(text.contains("camera id: 0"))
        assertTrue(text.contains("logical: false"))
        assertTrue(text.contains("unavailable"))
    }

    @Test
    fun `text starts with the CAM-2c header line`() {
        assertTrue(buildCameraCalibrationDiagnosticText(diagnostics).startsWith("CAM-2c calibration"))
    }
}
