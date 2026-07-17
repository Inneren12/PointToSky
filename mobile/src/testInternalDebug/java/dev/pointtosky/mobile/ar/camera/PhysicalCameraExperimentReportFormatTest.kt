package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
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

    // --- buildPhysicalCameraExperimentReportText (task §1: report reflects state progression) ---

    private val fullFrameTransform =
        SensorToBufferMatrix3(
            m00 = 640.0 / 4032.0, m01 = 0.0, m02 = 0.0,
            m10 = 0.0, m11 = 480.0 / 3024.0, m12 = 0.0,
            m20 = 0.0, m21 = 0.0, m22 = 1.0,
        )

    private fun frame() =
        CameraFrameMetadata(
            timestampNanos = 1L,
            bufferWidthPx = 640,
            bufferHeightPx = 480,
            rotationDegrees = 0,
            sensorToBufferTransform = fullFrameTransform,
        )

    private fun boundResolution() =
        PhysicalCameraBindingResolution.Bound(
            provenance = provenance,
            physicalCharacteristicsSnapshot =
                CameraCharacteristicsSnapshot(
                    availableFocalLengthsMm = floatArrayOf(3.6f),
                    sensorPhysicalWidthMm = 6.4f,
                    sensorPhysicalHeightMm = 4.8f,
                    activeArrayLeftPx = 0,
                    activeArrayTopPx = 0,
                    activeArrayRightPx = 4032,
                    activeArrayBottomPx = 3024,
                    pixelArrayWidthPx = 4032,
                    pixelArrayHeightPx = 3024,
                    isLogicalMultiCamera = false,
                    cameraId = "2",
                ),
        )

    @Test
    fun `no session yet renders SELECTING_CANDIDATE, not a crash or blank text`() {
        val text = buildPhysicalCameraExperimentReportText(null)

        assertTrue(text.contains("status=SELECTING_CANDIDATE"))
    }

    @Test
    fun `report progresses from awaiting-everything to a concrete frame and DOMAIN_NOT_PROVEN`() {
        // Reproduces the exact task §1 regression scenario end-to-end at the report level: a fresh
        // session (no binding, no frame) must render "none yet"/"awaiting frame"; once both a resolved
        // binding and a frame are folded in via the pure reducers - regardless of which arrived first -
        // the report must show the concrete frame size and DOMAIN_NOT_PROVEN, never a stale/blocked
        // "awaiting frame" left over from the first render.
        val fresh = initialExperimentSessionState(attemptId = 7L, physicalCameraId = "2")
        val freshText = buildPhysicalCameraExperimentReportText(fresh)
        assertTrue(freshText.contains("latestFrame=none yet"), freshText)
        assertTrue(freshText.contains("cam2cResult=awaiting frame"), freshText)

        val bound = fresh.reduceCameraInfoResolved(7L, boundResolution())
        val boundText = buildPhysicalCameraExperimentReportText(bound)
        assertTrue(boundText.contains("latestFrame=none yet"), boundText)
        assertTrue(boundText.contains("cam2cResult=awaiting frame"), boundText)

        val withFrame = bound.reduceFrame(7L, frame())
        val finalText = buildPhysicalCameraExperimentReportText(withFrame)
        assertTrue(finalText.contains("latestFrame=640x480"), finalText)
        assertTrue(finalText.contains("cam2cResult=DOMAIN_NOT_PROVEN"), finalText)
        assertFalse(finalText.contains("awaiting frame"), finalText)
    }

    @Test
    fun `a terminally failed session renders the failure and omits binding_frame detail`() {
        val failed =
            initialExperimentSessionState(attemptId = 3L, physicalCameraId = "2")
                .reduceExplicitBindFailure(3L, "explicit_selector_zoom_failed")

        val text = buildPhysicalCameraExperimentReportText(failed)

        assertTrue(text.contains("status=EXPLICIT_BIND_FAILED"), text)
        assertTrue(text.contains("reason=explicit_selector_zoom_failed"), text)
        assertFalse(text.contains("latestFrame="), text)
    }
}
