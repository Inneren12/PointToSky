package dev.pointtosky.core.astro.projection.camera.match

import dev.pointtosky.core.astro.projection.camera.CameraGeometryQuality
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsics
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsReference
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsResolution
import dev.pointtosky.core.astro.projection.camera.CameraIntrinsicsSource
import dev.pointtosky.core.astro.projection.camera.PixelPoint
import dev.pointtosky.core.astro.projection.camera.prediction.BufferOpticalCameraVector
import dev.pointtosky.core.astro.projection.camera.prediction.PinholeProjectionModel
import dev.pointtosky.core.astro.projection.camera.prediction.analysisBufferIntrinsics
import dev.pointtosky.core.astro.projection.camera.prediction.buildTestGeometry
import dev.pointtosky.core.astro.projection.camera.prediction.resolvedIntrinsics
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan
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
 *
 * The angular extents get their own fixtures with a deliberately off-centre principal point, because a
 * centred one is exactly the case in which a hidden `W / 2` assumption is invisible.
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
        // space. This is the *default* when no principal point was measured, never an assumption the
        // angular quantities below are allowed to make.
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
    fun `cameraRayFor delegates to the production inverse`() {
        val scale = AnalysisBufferScale.forGeometry(geometry())
        val point = PixelPoint(417.3, 118.9)

        assertEquals(scale.pinhole.unprojectToCameraRay(point), scale.cameraRayFor(point))
    }

    @Test
    fun `the optical axis ray is the forward axis, wherever the principal point sits`() {
        for (scale in listOf(AnalysisBufferScale.forGeometry(geometry()), offCentreScale())) {
            val axis = scale.opticalAxisRay

            assertEquals(0.0, axis.x, 1e-15)
            assertEquals(0.0, axis.y, 1e-15)
            assertEquals(1.0, axis.z, 1e-15)
        }
    }

    @Test
    fun `a centred principal point preserves the existing symmetric field of view`() {
        val scale = AnalysisBufferScale.forGeometry(geometry())

        // The value the old closed form produced, and the FOV the intrinsics reported: with the axis at
        // the raster centre the per-edge derivation must agree with both, exactly.
        assertEquals(Math.toRadians(horizontalFovDeg), scale.horizontalFieldOfViewRad, 1e-12)
        assertEquals(Math.toRadians(verticalFovDeg), scale.verticalFieldOfViewRad, 1e-12)
        assertEquals(
            2.0 * atan(scale.imageWidthPx / (2.0 * scale.focalLengthXPx)),
            scale.horizontalFieldOfViewRad,
            1e-12,
        )
        assertEquals(
            2.0 * atan(scale.imageHeightPx / (2.0 * scale.focalLengthYPx)),
            scale.verticalFieldOfViewRad,
            1e-12,
        )

        // Symmetric, because the axis is centred — the case that cannot detect a W/2 assumption.
        assertEquals(scale.leftAngularExtentRad, scale.rightAngularExtentRad, 1e-12)
        assertEquals(scale.topAngularExtentRad, scale.bottomAngularExtentRad, 1e-12)
    }

    @Test
    fun `an off-centre principal point produces unequal left and right extents`() {
        val scale = offCentreScale()

        assertTrue(
            scale.principalPointXPx != scale.imageWidthPx / 2.0,
            "the fixture must actually be off centre to test anything",
        )
        assertTrue(
            scale.leftAngularExtentRad < scale.rightAngularExtentRad,
            "axis left of centre must see less sky to its left; " +
                "left=${scale.leftAngularExtentRad} right=${scale.rightAngularExtentRad}",
        )
        assertTrue(
            scale.topAngularExtentRad > scale.bottomAngularExtentRad,
            "axis below centre must see more sky above it; " +
                "top=${scale.topAngularExtentRad} bottom=${scale.bottomAngularExtentRad}",
        )

        // The exact per-edge geometry, from the pixel offsets the model actually carries.
        assertEquals(atan(OFF_CENTRE_CX / scale.focalLengthXPx), scale.leftAngularExtentRad, 1e-12)
        assertEquals(
            atan((scale.imageWidthPx - OFF_CENTRE_CX) / scale.focalLengthXPx),
            scale.rightAngularExtentRad,
            1e-12,
        )
        assertEquals(atan(OFF_CENTRE_CY / scale.focalLengthYPx), scale.topAngularExtentRad, 1e-12)
        assertEquals(
            atan((scale.imageHeightPx - OFF_CENTRE_CY) / scale.focalLengthYPx),
            scale.bottomAngularExtentRad,
            1e-12,
        )
    }

    @Test
    fun `the full extent is the angle between the rays at the two opposing image edges`() {
        val scale = offCentreScale()

        val leftRay = scale.cameraRayFor(PixelPoint(0.0, scale.principalPointYPx))
        val rightRay = scale.cameraRayFor(PixelPoint(scale.imageWidthPx, scale.principalPointYPx))
        val topRay = scale.cameraRayFor(PixelPoint(scale.principalPointXPx, 0.0))
        val bottomRay = scale.cameraRayFor(PixelPoint(scale.principalPointXPx, scale.imageHeightPx))

        assertEquals(angleBetween(leftRay, rightRay), scale.horizontalFieldOfViewRad, 1e-12)
        assertEquals(angleBetween(topRay, bottomRay), scale.verticalFieldOfViewRad, 1e-12)
    }

    @Test
    fun `no centred-axis assumption survives in the calibrated path`() {
        val scale = offCentreScale()

        // The formula this replaced. It is exact only for a centred axis, so on this fixture it must be
        // visibly wrong — if it still agreed, a W/2 assumption would be hiding somewhere.
        val centredAxisFormula = 2.0 * atan(scale.imageWidthPx / (2.0 * scale.focalLengthXPx))
        assertTrue(
            abs(centredAxisFormula - scale.horizontalFieldOfViewRad) > 1e-3,
            "the centred-axis formula should disagree here; formula=$centredAxisFormula " +
                "actual=${scale.horizontalFieldOfViewRad}",
        )
        assertEquals(CameraGeometryQuality.CALIBRATED, scale.quality)
    }

    @Test
    fun `angular extents are unchanged by the orientation flags`() {
        // axisSwapped relabels which camera axis an image axis drives; it cannot change how far the
        // frame reaches in angle. Deriving the extents through the canonical inverse — rather than from
        // a closed form that would need its own special case — is what makes this hold.
        val plain = offCentreScale()
        val swapped = offCentreScale(axisSwapped = true, negateXInput = true)

        assertEquals(plain.leftAngularExtentRad, swapped.leftAngularExtentRad, 1e-12)
        assertEquals(plain.rightAngularExtentRad, swapped.rightAngularExtentRad, 1e-12)
        assertEquals(plain.topAngularExtentRad, swapped.topAngularExtentRad, 1e-12)
        assertEquals(plain.bottomAngularExtentRad, swapped.bottomAngularExtentRad, 1e-12)
        assertEquals(plain.enclosingConeRadiusRad, swapped.enclosingConeRadiusRad, 1e-12)

        // ...while the rays themselves genuinely differ, so the equality above is not vacuous.
        val point = PixelPoint(417.3, 118.9)
        assertTrue(
            angleBetween(plain.cameraRayFor(point), swapped.cameraRayFor(point)) > 1e-3,
            "the fixture must actually exercise the flags",
        )
    }

    @Test
    fun `the enclosing cone covers every image corner and is not half the horizontal extent`() {
        val scale = offCentreScale()

        val corners =
            listOf(
                PixelPoint(0.0, 0.0),
                PixelPoint(scale.imageWidthPx, 0.0),
                PixelPoint(0.0, scale.imageHeightPx),
                PixelPoint(scale.imageWidthPx, scale.imageHeightPx),
            )
        for (corner in corners) {
            val angle = angleBetween(scale.opticalAxisRay, scale.cameraRayFor(corner))
            assertTrue(
                angle <= scale.enclosingConeRadiusRad + 1e-12,
                "corner $corner at $angle rad falls outside the cone ${scale.enclosingConeRadiusRad}",
            )
        }

        // A corner reaches further than any edge midpoint, so sizing a cone from half the horizontal
        // extent would clip the frame — the mistake the property exists to prevent.
        assertTrue(
            scale.enclosingConeRadiusRad > scale.horizontalFieldOfViewRad / 2.0,
            "the cone must exceed half the horizontal extent; cone=${scale.enclosingConeRadiusRad}",
        )
        assertTrue(
            scale.enclosingConeRadiusRad > scale.rightAngularExtentRad,
            "the cone must exceed the widest single-axis extent",
        )
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
        // the KDoc is a measured fact rather than a disclaimer, and so the reason a matcher must use
        // cameraRayFor instead is a number rather than an opinion.
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

    /** A calibrated scale whose optical axis is deliberately nowhere near the raster centre. */
    private fun offCentreScale(
        axisSwapped: Boolean = false,
        negateXInput: Boolean = false,
        negateYInput: Boolean = false,
    ): AnalysisBufferScale =
        AnalysisBufferScale.forGeometry(
            geometry(
                calibratedAnalysisBufferIntrinsics(
                    principalPointXPx = OFF_CENTRE_CX,
                    principalPointYPx = OFF_CENTRE_CY,
                    axisSwapped = axisSwapped,
                    negateXInput = negateXInput,
                    negateYInput = negateYInput,
                ),
            ),
        )

    /**
     * A real per-device measurement that is also referenced to this exact analysis buffer — the
     * `CALIBRATED` counterpart to [analysisBufferIntrinsics]' legacy fallback, and the only source that
     * is allowed to carry a measured principal point or a non-default orientation flag (see
     * [CameraIntrinsics]' own invariants). Built here rather than added to the shared prediction
     * fixtures, which have no calibrated-and-buffer-referenced case because no prediction test needs to
     * tell the two qualities apart.
     */
    private fun calibratedAnalysisBufferIntrinsics(
        principalPointXPx: Double? = null,
        principalPointYPx: Double? = null,
        axisSwapped: Boolean = false,
        negateXInput: Boolean = false,
        negateYInput: Boolean = false,
    ): CameraIntrinsicsResolution.Resolved =
        CameraIntrinsicsResolution.Resolved(
            CameraIntrinsics(
                horizontalFovDeg = horizontalFovDeg,
                verticalFovDeg = verticalFovDeg,
                focalLengthMm = 4.0,
                sensorWidthMm = 5.0,
                sensorHeightMm = 4.0,
                principalPointXPx = principalPointXPx,
                principalPointYPx = principalPointYPx,
                source = CameraIntrinsicsSource.CAMERA_CHARACTERISTICS,
                reference = CameraIntrinsicsReference.AnalysisBuffer(bufferWidthPx, bufferHeightPx),
                axisSwapped = axisSwapped,
                negateXInput = negateXInput,
                negateYInput = negateYInput,
            ),
        )

    /** The angle between two unit camera rays, straight from their dot product — what a consumer does. */
    private fun angleBetween(
        a: BufferOpticalCameraVector,
        b: BufferOpticalCameraVector,
    ): Double = acos((a.x * b.x + a.y * b.y + a.z * b.z).coerceIn(-1.0, 1.0))

    private companion object {
        /** Left of centre (centre is 320) and above centre in image terms (centre is 240). */
        const val OFF_CENTRE_CX = 214.0
        const val OFF_CENTRE_CY = 301.0
    }
}
