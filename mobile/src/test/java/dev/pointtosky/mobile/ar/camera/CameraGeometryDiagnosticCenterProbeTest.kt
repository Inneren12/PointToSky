package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.CropScaleTransform
import dev.pointtosky.core.astro.projection.camera.PixelRect
import dev.pointtosky.core.astro.projection.camera.PixelSize
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * JVM tests for the CAM-1g center-probe diagnostic (§7): for a variety of [CropScaleTransform]
 * geometries, mapping the viewport center through `displayToImage` and back through `imageToDisplay`
 * must return (very close to) the original viewport center - this is the transform's own internal
 * self-consistency check, not proof of Preview/ImageAnalysis pixel alignment.
 */
class CameraGeometryDiagnosticCenterProbeTest {
    private val epsilon = 1e-6

    @Test
    fun `unrotated full-buffer transform round-trips the viewport center`() {
        val transform =
            CropScaleTransform.fillCenter(
                sourceCrop = PixelRect(0.0, 0.0, 1920.0, 1080.0),
                sourceBufferSize = PixelSize(1920.0, 1080.0),
                rotationDegrees = 0,
                viewportSize = PixelSize(1080.0, 1080.0),
            )
        val probe = computeCameraGeometryCenterProbe(transform)

        assertTrue(abs(probe.viewportCenterXPx - 540.0) < epsilon)
        assertTrue(abs(probe.viewportCenterYPx - 540.0) < epsilon)
        assertTrue(abs(probe.imagePointXPx - 960.0) < epsilon)
        assertTrue(abs(probe.imagePointYPx - 540.0) < epsilon)
        assertTrue(probe.roundTripErrorPx < epsilon)
    }

    @Test
    fun `90 degree rotation (portrait viewport) round-trips within tolerance`() {
        val transform =
            CropScaleTransform.fillCenter(
                sourceCrop = PixelRect(0.0, 0.0, 4000.0, 3000.0),
                sourceBufferSize = PixelSize(4000.0, 3000.0),
                rotationDegrees = 90,
                viewportSize = PixelSize(1080.0, 1920.0),
            )
        val probe = computeCameraGeometryCenterProbe(transform)
        assertTrue(probe.roundTripErrorPx < epsilon)
    }

    @Test
    fun `180 degree rotation round-trips within tolerance`() {
        val transform =
            CropScaleTransform.fillCenter(
                sourceCrop = PixelRect(0.0, 0.0, 1920.0, 1080.0),
                sourceBufferSize = PixelSize(1920.0, 1080.0),
                rotationDegrees = 180,
                viewportSize = PixelSize(1080.0, 1920.0),
            )
        val probe = computeCameraGeometryCenterProbe(transform)
        assertTrue(probe.roundTripErrorPx < epsilon)
    }

    @Test
    fun `270 degree rotation (landscape viewport) round-trips within tolerance`() {
        val transform =
            CropScaleTransform.fillCenter(
                sourceCrop = PixelRect(0.0, 0.0, 4000.0, 3000.0),
                sourceBufferSize = PixelSize(4000.0, 3000.0),
                rotationDegrees = 270,
                viewportSize = PixelSize(1920.0, 1080.0),
            )
        val probe = computeCameraGeometryCenterProbe(transform)
        assertTrue(probe.roundTripErrorPx < epsilon)
    }

    @Test
    fun `non-zero crop origin round-trips within tolerance`() {
        val transform =
            CropScaleTransform.fillCenter(
                sourceCrop = PixelRect(100.0, 50.0, 1900.0, 1000.0),
                sourceBufferSize = PixelSize(1920.0, 1080.0),
                rotationDegrees = 0,
                viewportSize = PixelSize(1080.0, 1920.0),
            )
        val probe = computeCameraGeometryCenterProbe(transform)
        assertTrue(probe.roundTripErrorPx < epsilon)
    }

    @Test
    fun `horizontal center crop (wide source into a narrower viewport) round-trips within tolerance`() {
        val transform =
            CropScaleTransform.fillCenter(
                sourceCrop = PixelRect(0.0, 0.0, 1920.0, 1080.0),
                sourceBufferSize = PixelSize(1920.0, 1080.0),
                rotationDegrees = 0,
                viewportSize = PixelSize(1080.0, 1080.0),
            )
        val probe = computeCameraGeometryCenterProbe(transform)
        assertTrue(probe.roundTripErrorPx < epsilon)
    }

    @Test
    fun `vertical center crop (square source into a taller-and-narrower viewport) round-trips within tolerance`() {
        val transform =
            CropScaleTransform.fillCenter(
                sourceCrop = PixelRect(0.0, 0.0, 1080.0, 1080.0),
                sourceBufferSize = PixelSize(1080.0, 1080.0),
                rotationDegrees = 0,
                viewportSize = PixelSize(1080.0, 1920.0),
            )
        val probe = computeCameraGeometryCenterProbe(transform)
        assertTrue(probe.roundTripErrorPx < epsilon)
    }

    @Test
    fun `round-trip error is always finite and never negative`() {
        val transform =
            CropScaleTransform.fillCenter(
                sourceCrop = PixelRect(10.0, 10.0, 1910.0, 1070.0),
                sourceBufferSize = PixelSize(1920.0, 1080.0),
                rotationDegrees = 90,
                viewportSize = PixelSize(1440.0, 2560.0),
            )
        val probe = computeCameraGeometryCenterProbe(transform)
        assertTrue(probe.roundTripErrorPx.isFinite())
        assertTrue(probe.roundTripErrorPx >= 0.0)
    }
}
