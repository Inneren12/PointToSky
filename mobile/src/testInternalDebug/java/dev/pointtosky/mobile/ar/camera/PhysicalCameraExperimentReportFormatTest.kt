package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransformClass
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure JVM tests for [formatCam2cResultLines] (fix for a units defect: a prior revision printed
 * `CameraIntrinsics.focalLengthMm` — a physical, millimetre-space quantity — labelled `"K: fx="`,
 * conflating it with the buffer-space pixel quantity `CameraCalibrationDiagnostics.bufferFxPx`).
 */
class PhysicalCameraExperimentReportFormatTest {
    private val provenance =
        PhysicalCameraProvenance(
            logicalCameraId = "0",
            physicalCameraId = "2",
            bindingMethod = PhysicalCameraBindingMethod.CAMERA_SELECTOR_PHYSICAL_CAMERA_ID,
            bindingSource = PhysicalCameraBindingSource.MATCHED_DECLARED_PHYSICAL_CAMERA_INFO,
            confidence = PhysicalCameraProvenanceConfidence.VERIFIED_BY_CHARACTERISTICS_IDENTITY,
        )

    private fun resolvedWith(
        bufferFxPx: Double,
        focalLengthMm: Double,
    ): Cam2cPhysicalCameraResolution.Resolved {
        val diagnostics =
            CameraCalibrationDiagnostics(
                activeArrayWidthPx = 4032,
                activeArrayHeightPx = 3024,
                activeArrayLeftPx = 0.0,
                activeArrayTopPx = 0.0,
                activeArrayRightPx = 4032.0,
                activeArrayBottomPx = 3024.0,
                pixelArrayWidthPx = 4032,
                pixelArrayHeightPx = 3024,
                sensorWidthMm = 6.4,
                sensorHeightMm = 4.8,
                focalLengthMm = focalLengthMm,
                activeFxPx = bufferFxPx * 6.3, // deliberately distinct from bufferFxPx too
                activeFyPx = bufferFxPx * 6.3,
                activeCxPx = 2016.0,
                activeCyPx = 1512.0,
                principalPointBasis = "ACTIVE_ARRAY_LOCAL",
                focalDerivationBasis = "PIXEL_ARRAY",
                cropLeftPx = 0.0,
                cropTopPx = 0.0,
                cropRightPx = 4032.0,
                cropBottomPx = 3024.0,
                bufferFxPx = bufferFxPx,
                bufferFyPx = bufferFxPx,
                bufferCxPx = 320.0,
                bufferCyPx = 240.0,
                quality = CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT,
                sensorToBufferMappingSource = "ImageInfo.getSensorToBufferTransformMatrix",
                transformClass = SensorToBufferTransformClass.AXIS_ALIGNED_0,
            )
        val intrinsics =
            CameraIntrinsics(
                horizontalFovDeg = 60.0,
                verticalFovDeg = 45.0,
                focalLengthMm = focalLengthMm,
                sensorWidthMm = 6.4,
                sensorHeightMm = 4.8,
                principalPointXPx = 320.0,
                principalPointYPx = 240.0,
                source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
                reference = CameraIntrinsicsReference.AnalysisBuffer(640, 480),
                quality = CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT,
            )
        return Cam2cPhysicalCameraResolution.Resolved(intrinsics, diagnostics, provenance)
    }

    @Test
    fun `resolved fxPx comes from bufferFxPx, never from the millimetre-space focalLengthMm`() {
        // A deliberately huge, unmistakable bufferFxPx (a real buffer-space pixel focal length is
        // typically hundreds to low-thousands of pixels) and a small, distinct focalLengthMm (a real
        // physical focal length is a few millimetres) - if the two were ever conflated, this
        // assertion would fail either way.
        val resolved = resolvedWith(bufferFxPx = 987654.0, focalLengthMm = 3.6)

        val text = formatCam2cResultLines(resolved)

        assertTrue(text.contains("fxPx=987654.0"), "expected the real buffer-space fxPx, got:\n$text")
        assertTrue(text.contains("focalLengthMm=3.6"), "focalLengthMm must still be printed, separately labelled:\n$text")
        assertFalse(text.contains("fx=3.6"), "fx must never be populated from focalLengthMm:\n$text")
    }

    @Test
    fun `domain-not-proven result never prints a K line at all`() {
        val text =
            formatCam2cResultLines(
                Cam2cPhysicalCameraResolution.DomainNotProven(SensorToBufferDomainProof.Unresolved, provenance),
            )

        assertFalse(text.contains("fxPx="))
        assertTrue(text.contains("DOMAIN_NOT_PROVEN"))
    }
}
