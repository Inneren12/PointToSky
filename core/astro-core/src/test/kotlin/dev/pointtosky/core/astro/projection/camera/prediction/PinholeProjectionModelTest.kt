package dev.pointtosky.core.astro.projection.camera.prediction

import kotlin.math.abs
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Section C anchors: hand-computed pinhole projection arithmetic.
 *
 * Worked example (from the CAM-2a spec): `image = 1000x500`, horizontal FOV `= vertical FOV = 90 deg`,
 * principal point = center.
 * ```text
 * fx = width  / (2 * tan(45 deg)) = 1000 / (2 * 1) = 500
 * fy = height / (2 * tan(45 deg)) =  500 / (2 * 1) = 250
 * ```
 * For a normalized point `x/z = 0.5`, `y/z = 0.0`: `u = 500*0.5 + 500 = 750`, `v = 250*0 + 250 = 250`.
 */
class PinholeProjectionModelTest {
    private val eps = 1e-9

    private fun workedExampleModel(): PinholeProjectionModel =
        PinholeProjectionModel(
            focalLengthXPx = 500.0,
            focalLengthYPx = 250.0,
            principalPointXPx = 500.0,
            principalPointYPx = 250.0,
            imageWidthPx = 1000.0,
            imageHeightPx = 500.0,
        )

    // --- Validation ------------------------------------------------------------------------------

    @Test
    fun `zero or negative focal length is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PinholeProjectionModel(0.0, 250.0, 500.0, 250.0, 1000.0, 500.0)
        }
        assertFailsWith<IllegalArgumentException> {
            PinholeProjectionModel(500.0, -1.0, 500.0, 250.0, 1000.0, 500.0)
        }
    }

    @Test
    fun `non-finite focal length or principal point is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PinholeProjectionModel(Double.NaN, 250.0, 500.0, 250.0, 1000.0, 500.0)
        }
        assertFailsWith<IllegalArgumentException> {
            PinholeProjectionModel(500.0, 250.0, Double.POSITIVE_INFINITY, 250.0, 1000.0, 500.0)
        }
    }

    @Test
    fun `zero or negative image dimensions are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            PinholeProjectionModel(500.0, 250.0, 500.0, 250.0, 0.0, 500.0)
        }
        assertFailsWith<IllegalArgumentException> {
            PinholeProjectionModel(500.0, 250.0, 500.0, 250.0, 1000.0, -1.0)
        }
    }

    // --- Worked example: derived fx/fy ------------------------------------------------------------

    @Test
    fun `derived focal lengths match the hand-computed worked example`() {
        val hFovRad = Math.toRadians(90.0)
        val vFovRad = Math.toRadians(90.0)
        val fx = 1000.0 / (2.0 * tan(hFovRad / 2.0))
        val fy = 500.0 / (2.0 * tan(vFovRad / 2.0))
        assertTrue(abs(fx - 500.0) < eps, "fx expected 500.0 but was $fx")
        assertTrue(abs(fy - 250.0) < eps, "fy expected 250.0 but was $fy")
    }

    // --- Worked example: center/right/left/top/bottom rays -----------------------------------------

    @Test
    fun `center ray projects to the principal point`() {
        val point = workedExampleModel().project(0.0, 0.0)
        assertEquals(500.0, point.x, eps)
        assertEquals(250.0, point.y, eps)
    }

    @Test
    fun `right ray at normalizedX 0,5 projects to u=750 per the worked example`() {
        val point = workedExampleModel().project(0.5, 0.0)
        assertEquals(750.0, point.x, eps)
        assertEquals(250.0, point.y, eps)
    }

    @Test
    fun `left ray at normalizedX minus 0,5 projects to u=250`() {
        val point = workedExampleModel().project(-0.5, 0.0)
        assertEquals(250.0, point.x, eps)
        assertEquals(250.0, point.y, eps)
    }

    @Test
    fun `top ray at normalizedY minus 0,5 projects to v=125 (smaller v is up)`() {
        val point = workedExampleModel().project(0.0, -0.5)
        assertEquals(500.0, point.x, eps)
        assertEquals(125.0, point.y, eps)
    }

    @Test
    fun `bottom ray at normalizedY 0,5 projects to v=375 (larger v is down)`() {
        val point = workedExampleModel().project(0.0, 0.5)
        assertEquals(500.0, point.x, eps)
        assertEquals(375.0, point.y, eps)
    }

    // --- Edge of frustum / just outside -------------------------------------------------------------

    @Test
    fun `normalizedX at exactly tan(halfHFov) lands exactly on the right image edge`() {
        val model = workedExampleModel()
        val halfHFovTan = tan(Math.toRadians(45.0)) // = 1.0 for a 90 deg horizontal FOV
        val point = model.project(halfHFovTan, 0.0)
        assertEquals(model.imageWidthPx, point.x, eps)
    }

    @Test
    fun `normalizedX just beyond tan(halfHFov) lands just outside the image, unclamped`() {
        val model = workedExampleModel()
        val justOutside = tan(Math.toRadians(45.0)) * 1.01
        val point = model.project(justOutside, 0.0)
        assertTrue(point.x > model.imageWidthPx, "expected ${point.x} to exceed imageWidthPx ${model.imageWidthPx}")
    }

    // --- Custom principal point ----------------------------------------------------------------------

    @Test
    fun `custom principal point shifts the center ray, not the focal length`() {
        val model =
            PinholeProjectionModel(
                focalLengthXPx = 500.0,
                focalLengthYPx = 250.0,
                principalPointXPx = 600.0,
                principalPointYPx = 200.0,
                imageWidthPx = 1000.0,
                imageHeightPx = 500.0,
            )
        val center = model.project(0.0, 0.0)
        assertEquals(600.0, center.x, eps)
        assertEquals(200.0, center.y, eps)

        val right = model.project(0.5, 0.0)
        assertEquals(850.0, right.x, eps) // 500*0.5 + 600
    }

    // --- forGeometry: buffer-space, analysis-buffer-referenced intrinsics only ----------------------
    //
    // forGeometry requires `referenceSpace == ANALYSIS_BUFFER` (Blocker 2): a physical-sensor
    // (`CAMERA_CHARACTERISTICS`) intrinsics value's FOV is not known to map onto the analysis buffer
    // (CameraX may crop/scale the sensor into that stream, and this codebase captures no such
    // crop/scale metadata), so forGeometry throws for it rather than silently misapplying it. See the
    // dedicated rejection test below and `CameraStarPredictor`'s `IntrinsicsMappingUnavailable` path,
    // which is what callers should actually check before ever calling forGeometry directly.

    @Test
    fun `forGeometry derives fx,fy from analysis-buffer-referenced intrinsics FOV over the full buffer`() {
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1000,
                bufferHeightPx = 500,
                intrinsicsResolution = analysisBufferIntrinsics(horizontalFovDeg = 60.0, verticalFovDeg = 40.0),
            )
        val model = PinholeProjectionModel.forGeometry(geometry)

        val expectedFx = 1000.0 / (2.0 * tan(Math.toRadians(60.0) / 2.0))
        val expectedFy = 500.0 / (2.0 * tan(Math.toRadians(40.0) / 2.0))
        assertEquals(expectedFx, model.focalLengthXPx, 1e-6)
        assertEquals(expectedFy, model.focalLengthYPx, 1e-6)
        assertEquals(500.0, model.principalPointXPx, eps) // no principal point supplied -> buffer center
        assertEquals(250.0, model.principalPointYPx, eps)
        assertEquals(1000.0, model.imageWidthPx, eps)
        assertEquals(500.0, model.imageHeightPx, eps)
    }

    @Test
    fun `forGeometry derives fx,fy from the legacy-fallback intrinsics using the same formula`() {
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1000,
                bufferHeightPx = 500,
                intrinsicsResolution = legacyFallbackIntrinsics(imageWidthPx = 1000, imageHeightPx = 500),
            )
        val model = PinholeProjectionModel.forGeometry(geometry)
        val fallbackIntrinsics = geometry.intrinsics.intrinsics

        val expectedFx = 1000.0 / (2.0 * tan(Math.toRadians(fallbackIntrinsics.horizontalFovDeg) / 2.0))
        val expectedFy = 500.0 / (2.0 * tan(Math.toRadians(fallbackIntrinsics.verticalFovDeg) / 2.0))
        assertEquals(expectedFx, model.focalLengthXPx, 1e-6)
        assertEquals(expectedFy, model.focalLengthYPx, 1e-6)
    }

    @Test
    fun `forGeometry uses the full analyzed buffer, not the crop or the viewport`() {
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 2000,
                bufferHeightPx = 1000,
                cropRectLeftPx = 100,
                cropRectTopPx = 100,
                cropRectRightPx = 900,
                cropRectBottomPx = 700,
                viewportWidthPx = 400,
                viewportHeightPx = 800,
            )
        val model = PinholeProjectionModel.forGeometry(geometry)
        assertEquals(2000.0, model.imageWidthPx, eps)
        assertEquals(1000.0, model.imageHeightPx, eps)
        assertEquals(1000.0, model.principalPointXPx, eps) // buffer center, not crop or viewport center
        assertEquals(500.0, model.principalPointYPx, eps)
    }

    @Test
    fun `forGeometry uses a real principal point from intrinsics when present`() {
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1000,
                bufferHeightPx = 500,
                intrinsicsResolution = analysisBufferIntrinsics(principalPointXPx = 480.0, principalPointYPx = 260.0),
            )
        val model = PinholeProjectionModel.forGeometry(geometry)
        assertEquals(480.0, model.principalPointXPx, eps)
        assertEquals(260.0, model.principalPointYPx, eps)
    }

    // --- forGeometry: physical-sensor-referenced intrinsics are rejected, never silently used -------

    @Test
    fun `forGeometry throws for physical-sensor-referenced (calibrated) intrinsics`() {
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1000,
                bufferHeightPx = 500,
                intrinsicsResolution = resolvedIntrinsics(horizontalFovDeg = 60.0, verticalFovDeg = 40.0),
            )
        assertFailsWith<IllegalArgumentException> {
            PinholeProjectionModel.forGeometry(geometry)
        }
    }

    private fun assertEquals(
        expected: Double,
        actual: Double,
        tolerance: Double,
    ) {
        assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")
    }
}
