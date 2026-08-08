package dev.pointtosky.core.astro.projection.camera.match

import dev.pointtosky.core.astro.projection.camera.CameraGeometryQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel
import dev.pointtosky.core.astro.projection.camera.prediction.analysisBufferIntrinsics
import dev.pointtosky.core.astro.projection.camera.prediction.buildTestGeometry
import dev.pointtosky.core.astro.projection.camera.prediction.resolvedIntrinsics
import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the matcher's scale carrier to the **production** projection rather than to a formula rewritten
 * here: every assertion below either reads a number straight off
 * [PinholeProjectionModel.forGeometry]'s own output or checks an identity that would break if the
 * carrier had re-derived anything of its own.
 */
class AnalysisBufferScaleTest {
    private val bufferWidthPx = 640
    private val bufferHeightPx = 480
    private val horizontalFovDeg = 66.0
    private val verticalFovDeg = 52.0

    private fun geometry(
        intrinsics: CameraIntrinsicsResolution =
            analysisBufferIntrinsics(
                referenceWidthPx = bufferWidthPx,
                referenceHeightPx = bufferHeightPx,
                horizontalFovDeg = horizontalFovDeg,
                verticalFovDeg = verticalFovDeg,
            ),
    ) = buildTestGeometry(
        bufferWidthPx = bufferWidthPx,
        bufferHeightPx = bufferHeightPx,
        viewportWidthPx = bufferWidthPx,
        viewportHeightPx = bufferHeightPx,
        intrinsicsResolution = intrinsics,
    )

    @Test
    fun `carries the production pinhole model rather than a second derivation of it`() {
        val geometry = geometry()

        val scale = AnalysisBufferScale.forGeometry(geometry)

        // Value-equal to what projectStars projects with. Storing the model (instead of unpacking it
        // into independent fields) is what makes this true by construction.
        assertEquals(PinholeProjectionModel.forGeometry(geometry), scale.pinhole)
        assertEquals(scale.pinhole.focalLengthXPx, scale.focalLengthXPx)
        assertEquals(scale.pinhole.focalLengthYPx, scale.focalLengthYPx)
        assertEquals(scale.pinhole.principalPointXPx, scale.principalPointXPx)
        assertEquals(scale.pinhole.principalPointYPx, scale.principalPointYPx)
        assertEquals(scale.pinhole.imageWidthPx, scale.imageWidthPx)
        assertEquals(scale.pinhole.imageHeightPx, scale.imageHeightPx)
    }

    @Test
    fun `the principal point is the buffer centre under the edge-coordinate convention`() {
        val scale = AnalysisBufferScale.forGeometry(geometry())

        // W/2, H/2 — the buffer's exact geometric centre under the continuous edge-coordinate
        // convention. Under a pixel-centre convention it would be 319.5/239.5 instead, which is the
        // half-pixel PixelConventionBridgeTest exists to catch on the detector's side of the same
        // space.
        assertEquals(320.0, scale.principalPointXPx)
        assertEquals(240.0, scale.principalPointYPx)
        assertEquals(bufferWidthPx / 2.0, scale.principalPointXPx)
        assertEquals(bufferHeightPx / 2.0, scale.principalPointYPx)

        // And it is the point the on-axis ray actually lands on, not merely a number that looks central.
        val onAxis = scale.pinhole.project(normalizedX = 0.0, normalizedY = 0.0)
        assertEquals(scale.principalPointXPx, onAxis.x)
        assertEquals(scale.principalPointYPx, onAxis.y)
    }

    @Test
    fun `focal lengths follow the FOV the intrinsics reported`() {
        val scale = AnalysisBufferScale.forGeometry(geometry())

        val expectedFx = bufferWidthPx / (2.0 * tan(Math.toRadians(horizontalFovDeg) / 2.0))
        val expectedFy = bufferHeightPx / (2.0 * tan(Math.toRadians(verticalFovDeg) / 2.0))
        assertEquals(expectedFx, scale.focalLengthXPx, 1e-9)
        assertEquals(expectedFy, scale.focalLengthYPx, 1e-9)
    }

    @Test
    fun `the field of view round-trips to the intrinsics it was derived from`() {
        val scale = AnalysisBufferScale.forGeometry(geometry())

        assertEquals(Math.toRadians(horizontalFovDeg), scale.horizontalFieldOfViewRad, 1e-12)
        assertEquals(Math.toRadians(verticalFovDeg), scale.verticalFieldOfViewRad, 1e-12)
    }

    @Test
    fun `the on-axis plate scale is the derivative of the projection at the optical axis`() {
        val scale = AnalysisBufferScale.forGeometry(geometry())

        assertEquals(1.0 / scale.focalLengthXPx, scale.radiansPerPixelXOnAxis)
        assertEquals(1.0 / scale.focalLengthYPx, scale.radiansPerPixelYOnAxis)

        // Measured against the real projection: a ray one plate-scale off the axis lands one pixel off
        // the principal point, to first order. tan() curvature over one pixel is far below this bound.
        val oneScaleOffAxis = scale.pinhole.project(normalizedX = tan(scale.radiansPerPixelXOnAxis), normalizedY = 0.0)
        assertEquals(1.0, oneScaleOffAxis.x - scale.principalPointXPx, 1e-5)

        // A whole frame width is nowhere near this linear — asserted so the "on-axis only" caveat in
        // the KDoc is a measured fact rather than a disclaimer.
        val halfWidthPx = bufferWidthPx / 2.0
        val edgeAngleRad = Math.toRadians(horizontalFovDeg) / 2.0
        val linearGuess = halfWidthPx * scale.radiansPerPixelXOnAxis
        assertTrue(
            abs(linearGuess - edgeAngleRad) > 0.1 * edgeAngleRad,
            "the on-axis scale should be visibly wrong at the frame edge; guess=$linearGuess vs true=$edgeAngleRad",
        )
    }

    @Test
    fun `quality rides along with the numbers so a fallback scale cannot be read as calibrated`() {
        val fallback = AnalysisBufferScale.forGeometry(geometry())
        assertEquals(CameraGeometryQuality.LEGACY_INTRINSICS_FALLBACK, fallback.quality)

        val calibrated = AnalysisBufferScale.forGeometry(geometry(calibratedAnalysisBufferIntrinsics()))
        assertEquals(CameraGeometryQuality.CALIBRATED, calibrated.quality)
    }

    @Test
    fun `unmappable intrinsics throw rather than yielding a fabricated scale`() {
        // A physical-sensor-referenced FOV has no recorded relationship to this analysis buffer, so
        // there is no honest scale to report. projectStars reports the same case as
        // IntrinsicsMappingUnavailable; the carrier must not paper over it with a guess.
        val geometry = geometry(resolvedIntrinsics())

        assertFailsWith<IllegalArgumentException> { AnalysisBufferScale.forGeometry(geometry) }
    }

    @Test
    fun `a stale analysis-buffer reference is rejected, not silently reused`() {
        val geometry =
            geometry(
                analysisBufferIntrinsics(
                    referenceWidthPx = bufferWidthPx * 2,
                    referenceHeightPx = bufferHeightPx * 2,
                    horizontalFovDeg = horizontalFovDeg,
                    verticalFovDeg = verticalFovDeg,
                ),
            )

        assertFailsWith<IllegalArgumentException> { AnalysisBufferScale.forGeometry(geometry) }
    }

    @Test
    fun `the carrier stores the model it was handed`() {
        val model = PinholeProjectionModel.forGeometry(geometry())

        val scale = AnalysisBufferScale(model, CameraGeometryQuality.CALIBRATED)

        assertSame(model, scale.pinhole)
    }

    /**
     * A real per-device measurement that is also referenced to this exact analysis buffer — the
     * `CALIBRATED` counterpart to [analysisBufferIntrinsics]' legacy fallback. Built here rather than
     * added to the shared prediction fixtures, which have no calibrated-and-buffer-referenced case
     * because no prediction test needs to tell the two qualities apart.
     */
    private fun calibratedAnalysisBufferIntrinsics(): CameraIntrinsicsResolution.Resolved =
        CameraIntrinsicsResolution.Resolved(
            CameraIntrinsics(
                horizontalFovDeg = horizontalFovDeg,
                verticalFovDeg = verticalFovDeg,
                focalLengthMm = 4.0,
                sensorWidthMm = 5.0,
                sensorHeightMm = 4.0,
                principalPointXPx = null,
                principalPointYPx = null,
                source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
                reference = CameraIntrinsicsReference.AnalysisBuffer(bufferWidthPx, bufferHeightPx),
            ),
        )
}
