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

    // --- forGeometry: buffer-space, exactly-matching analysis-buffer-referenced intrinsics only -----
    //
    // forGeometry requires `reference` to be a `CameraIntrinsicsReference.AnalysisBuffer` whose
    // widthPx/heightPx exactly match geometry.frame's buffer (Blocker 2, hardened further to check
    // exact dimensions, not just a bare reference-space label): a physical-sensor
    // (`CAMERA_CHARACTERISTICS`) intrinsics value's FOV is not known to map onto the analysis buffer
    // (CameraX may crop/scale the sensor into that stream, and this codebase captures no such
    // crop/scale metadata), and an analysis-buffer intrinsics value resolved for a different buffer
    // size must not be silently reused either, so forGeometry throws for both rather than silently
    // misapplying them. See the dedicated rejection tests below and `CameraStarPredictor`'s
    // `IntrinsicsMappingUnavailable` path, which is what callers should actually check before ever
    // calling forGeometry directly.

    @Test
    fun `forGeometry derives fx,fy from analysis-buffer-referenced intrinsics FOV over the full buffer`() {
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1000,
                bufferHeightPx = 500,
                intrinsicsResolution =
                    analysisBufferIntrinsics(referenceWidthPx = 1000, referenceHeightPx = 500, horizontalFovDeg = 60.0, verticalFovDeg = 40.0),
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
                intrinsicsResolution =
                    analysisBufferIntrinsics(referenceWidthPx = 1000, referenceHeightPx = 500, principalPointXPx = 480.0, principalPointYPx = 260.0),
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

    // --- forGeometry: Unspecified (dimensionless) reference is rejected, never a guessed default -----

    @Test
    fun `forGeometry throws for an Unspecified (dimensionless) reference`() {
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1000,
                bufferHeightPx = 500,
                intrinsicsResolution = unspecifiedReferenceIntrinsics(),
            )
        assertFailsWith<IllegalArgumentException> {
            PinholeProjectionModel.forGeometry(geometry)
        }
    }

    // --- forGeometry: exact buffer-dimension compatibility, not merely matching aspect ratio ---------

    @Test
    fun `forGeometry throws when the AnalysisBuffer reference width does not match the geometry buffer width`() {
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1200,
                bufferHeightPx = 500,
                intrinsicsResolution = analysisBufferIntrinsics(referenceWidthPx = 1000, referenceHeightPx = 500),
            )
        assertFailsWith<IllegalArgumentException> {
            PinholeProjectionModel.forGeometry(geometry)
        }
    }

    @Test
    fun `forGeometry throws when the AnalysisBuffer reference height does not match the geometry buffer height`() {
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1000,
                bufferHeightPx = 600,
                intrinsicsResolution = analysisBufferIntrinsics(referenceWidthPx = 1000, referenceHeightPx = 500),
            )
        assertFailsWith<IllegalArgumentException> {
            PinholeProjectionModel.forGeometry(geometry)
        }
    }

    @Test
    fun `forGeometry throws for a same-aspect-ratio but differently-sized buffer - aspect match is not enough`() {
        // reference=1000x500 (2:1) vs geometry buffer=2000x1000 (also 2:1): same shape, different
        // absolute size. Silently accepting this would misapply a fx/fy derived for a half-size buffer.
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 2000,
                bufferHeightPx = 1000,
                intrinsicsResolution = analysisBufferIntrinsics(referenceWidthPx = 1000, referenceHeightPx = 500),
            )
        assertFailsWith<IllegalArgumentException> {
            PinholeProjectionModel.forGeometry(geometry)
        }
    }

    @Test
    fun `forGeometry throws for a different-aspect-ratio buffer`() {
        // reference=1920x1080 (16:9) vs geometry buffer=1440x1080 (4:3).
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1440,
                bufferHeightPx = 1080,
                intrinsicsResolution = analysisBufferIntrinsics(referenceWidthPx = 1920, referenceHeightPx = 1080),
            )
        assertFailsWith<IllegalArgumentException> {
            PinholeProjectionModel.forGeometry(geometry)
        }
    }

    @Test
    fun `forGeometry accepts an AnalysisBuffer reference that exactly matches the geometry buffer`() {
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1234,
                bufferHeightPx = 987,
                intrinsicsResolution = analysisBufferIntrinsics(referenceWidthPx = 1234, referenceHeightPx = 987),
            )
        val model = PinholeProjectionModel.forGeometry(geometry)
        assertEquals(1234.0, model.imageWidthPx, eps)
        assertEquals(987.0, model.imageHeightPx, eps)
    }

    private fun assertEquals(
        expected: Double,
        actual: Double,
        tolerance: Double,
    ) {
        assertTrue(abs(expected - actual) < tolerance, "expected $expected but was $actual")
    }

    // --- axisSwapped/negateXInput/negateYInput (CAM-2c fix §1/§2/§7) --------------------------------

    @Test
    fun `axisSwapped false is the exact pre-CAM-2c-fix formula - full backward compatibility`() {
        val model = workedExampleModel()
        val point = model.project(0.5, -0.5)
        assertEquals(750.0, point.x, eps) // fx*0.5 + cx
        assertEquals(125.0, point.y, eps) // fy*-0.5 + cy
    }

    @Test
    fun `axisSwapped true multiplies focalLengthXPx by normalizedY and focalLengthYPx by normalizedX`() {
        val model =
            PinholeProjectionModel(
                focalLengthXPx = 500.0,
                focalLengthYPx = 250.0,
                principalPointXPx = 500.0,
                principalPointYPx = 250.0,
                imageWidthPx = 1000.0,
                imageHeightPx = 500.0,
                axisSwapped = true,
            )
        val point = model.project(normalizedX = 0.1, normalizedY = 0.5)
        assertEquals(500.0 * 0.5 + 500.0, point.x, eps) // fx * normalizedY (not normalizedX)
        assertEquals(250.0 * 0.1 + 250.0, point.y, eps) // fy * normalizedX (not normalizedY)
    }

    @Test
    fun `negateXInput and negateYInput each independently flip the sign of their own input`() {
        val negateXOnly =
            PinholeProjectionModel(500.0, 250.0, 500.0, 250.0, 1000.0, 500.0, negateXInput = true)
        val negateYOnly =
            PinholeProjectionModel(500.0, 250.0, 500.0, 250.0, 1000.0, 500.0, negateYInput = true)
        val negateBoth =
            PinholeProjectionModel(500.0, 250.0, 500.0, 250.0, 1000.0, 500.0, negateXInput = true, negateYInput = true)

        val plain = workedExampleModel().project(0.3, 0.4)
        val flippedX = negateXOnly.project(0.3, 0.4)
        val flippedY = negateYOnly.project(0.3, 0.4)
        val flippedBoth = negateBoth.project(0.3, 0.4)

        assertEquals(2.0 * 500.0 - plain.x, flippedX.x, eps) // mirrored around principal point X
        assertEquals(plain.y, flippedX.y, eps) // Y untouched
        assertEquals(plain.x, flippedY.x, eps) // X untouched
        assertEquals(2.0 * 250.0 - plain.y, flippedY.y, eps) // mirrored around principal point Y
        assertEquals(2.0 * 500.0 - plain.x, flippedBoth.x, eps)
        assertEquals(2.0 * 250.0 - plain.y, flippedBoth.y, eps)
    }

    @Test
    fun `forGeometry wires axisSwapped and negate flags straight through from the resolved intrinsics`() {
        val geometry =
            buildTestGeometry(
                bufferWidthPx = 1000,
                bufferHeightPx = 500,
                intrinsicsResolution =
                    analysisBufferIntrinsics(
                        referenceWidthPx = 1000,
                        referenceHeightPx = 500,
                        axisSwapped = true,
                        negateXInput = false,
                        negateYInput = true,
                    ),
            )
        val model = PinholeProjectionModel.forGeometry(geometry)
        assertTrue(model.axisSwapped)
        assertTrue(!model.negateXInput)
        assertTrue(model.negateYInput)
    }

    // --- No double rotation (CAM-2c fix §1/§2/§7): axisSwapped's position-space swap is independent
    // of DisplayAlignedOpticalToBufferOpticalTransform's direction-space rotation --------------------

    @Test
    fun `an axisSwapped model and DisplayAlignedOpticalToBufferOpticalTransform rotation never compound into a double transform`() {
        // A ray whose display-aligned optical direction is (dx, dy, dz) = (0.2, 0.05, -1.0).
        val displayOptical = OpticalCameraVector(x = 0.2, y = 0.05, z = -1.0)

        // DisplayAlignedOpticalToBufferOpticalTransform's OWN 90-degree case (untouched by this fix):
        // bufferOptical.x = dy, bufferOptical.y = -dx (see that object's own mapping table).
        val bufferOptical = DisplayAlignedOpticalToBufferOpticalTransform.apply(displayOptical, rotationDegrees = 90)
        assertEquals(displayOptical.y, bufferOptical.x, eps)
        assertEquals(-displayOptical.x, bufferOptical.y, eps)

        // A pinhole model with axisSwapped=true, independently, swaps which of project()'s own two
        // *inputs* drives fx vs fy - a completely different operation, applied to whatever
        // normalizedX/normalizedY it is handed, with no awareness of rotationDegrees at all.
        val swappedModel =
            PinholeProjectionModel(
                focalLengthXPx = 400.0,
                focalLengthYPx = 300.0,
                principalPointXPx = 320.0,
                principalPointYPx = 240.0,
                imageWidthPx = 640.0,
                imageHeightPx = 480.0,
                axisSwapped = true,
            )
        val unswappedModel = swappedModel.copy(axisSwapped = false)

        // Feeding the SAME already-rotated (bufferOptical) ray through both models: the swapped
        // model's result is exactly the unswapped model's result with x/y's *contributions* swapped
        // (fx now multiplies bufferOptical.y, fy now multiplies bufferOptical.x) - never an extra
        // rotation of the (dx, dy) pair itself, and never dependent on rotationDegrees=90 having been
        // applied one line above.
        val swappedPoint = swappedModel.project(bufferOptical.x, bufferOptical.y)
        assertEquals(swappedModel.focalLengthXPx * bufferOptical.y + swappedModel.principalPointXPx, swappedPoint.x, eps)
        assertEquals(swappedModel.focalLengthYPx * bufferOptical.x + swappedModel.principalPointYPx, swappedPoint.y, eps)

        // Changing rotationDegrees (0 vs 90 vs 180 vs 270) changes bufferOptical; changing
        // axisSwapped changes how project() consumes whatever bufferOptical it is given. The two
        // never both apply to the same quantity - proven by these two computations using entirely
        // disjoint formulas (one only ever touches displayOptical -> bufferOptical, the other only
        // ever touches bufferOptical -> pixel).
        val unswappedPoint = unswappedModel.project(bufferOptical.x, bufferOptical.y)
        assertEquals(unswappedModel.focalLengthXPx * bufferOptical.x + unswappedModel.principalPointXPx, unswappedPoint.x, eps)
        assertTrue(
            abs(swappedPoint.x - unswappedPoint.x) > 1.0,
            "swapped and unswapped projections of the same ray must genuinely differ, not coincidentally match",
        )
    }
}
