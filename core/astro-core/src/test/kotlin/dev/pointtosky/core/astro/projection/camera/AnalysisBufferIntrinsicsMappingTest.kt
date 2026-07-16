package dev.pointtosky.core.astro.projection.camera

import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val EPS = 1e-6

/**
 * Pure JVM tests for [SensorToBufferMatrix3], [classifySensorToBufferMatrix],
 * [ActiveArrayRect], and [mapActiveArrayIntrinsicsThroughMatrix] (CAM-2c §4/§5/§8, fix
 * §1/§2/§7).
 */
class AnalysisBufferIntrinsicsMappingTest {
    private fun axisAlignedMatrix(
        scaleX: Double,
        scaleY: Double,
        translateX: Double,
        translateY: Double,
    ) = SensorToBufferMatrix3(
        m00 = scaleX, m01 = 0.0, m02 = translateX,
        m10 = 0.0, m11 = scaleY, m12 = translateY,
        m20 = 0.0, m21 = 0.0, m22 = 1.0,
    )

    // --- SensorToBufferMatrix3 ---

    @Test
    fun `SensorToBufferMatrix3 rejects any non-finite component`() {
        val bad = listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
        bad.forEach { value ->
            assertFailsWith<IllegalArgumentException> {
                SensorToBufferMatrix3(value, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
            }
        }
    }

    @Test
    fun `SensorToBufferMatrix3 accepts finite values including negative scale`() {
        val matrix = SensorToBufferMatrix3(-1.0, 0.0, 100.0, 0.0, -1.0, 100.0, 0.0, 0.0, 1.0)
        assertEquals(-1.0, matrix.m00)
        assertEquals(1.0, matrix.linearDeterminant, EPS) // (-1)*(-1) - 0*0 = 1
    }

    // --- classifySensorToBufferMatrix ---

    @Test
    fun `classifies pure positive scale plus translate as AXIS_ALIGNED_0`() {
        val matrix = axisAlignedMatrix(0.5, 0.5, -504.0, -378.0)
        assertEquals(SensorToBufferTransformClass.AXIS_ALIGNED_0, classifySensorToBufferMatrix(matrix))
    }

    @Test
    fun `classifies pure negative scale as ORTHOGONAL_180`() {
        val matrix = axisAlignedMatrix(-1.0, -1.0, 1000.0, 800.0)
        assertEquals(SensorToBufferTransformClass.ORTHOGONAL_180, classifySensorToBufferMatrix(matrix))
    }

    @Test
    fun `classifies the two 90-degree-like axis permutations distinctly`() {
        // bufferX = activeY, bufferY = W - activeX (a genuine 90-degree position rotation).
        val ninety = SensorToBufferMatrix3(0.0, 1.0, 0.0, -1.0, 0.0, 1000.0, 0.0, 0.0, 1.0)
        assertEquals(SensorToBufferTransformClass.ORTHOGONAL_90, classifySensorToBufferMatrix(ninety))

        // The inverse rotation: bufferX = H - activeY, bufferY = activeX.
        val twoSeventy = SensorToBufferMatrix3(0.0, -1.0, 1000.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        assertEquals(SensorToBufferTransformClass.ORTHOGONAL_270, classifySensorToBufferMatrix(twoSeventy))
    }

    @Test
    fun `classifies a single-axis flip as MIRRORED`() {
        val matrix = axisAlignedMatrix(-1.0, 1.0, 1000.0, 0.0)
        assertEquals(SensorToBufferTransformClass.MIRRORED, classifySensorToBufferMatrix(matrix))
    }

    @Test
    fun `classifies a same-sign anti-diagonal swap as MIRRORED`() {
        // m00=m11~0, m01 and m10 same sign -> det < 0, a mirrored swap, not a proper rotation.
        val matrix = SensorToBufferMatrix3(0.0, 1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        assertEquals(SensorToBufferTransformClass.MIRRORED, classifySensorToBufferMatrix(matrix))
    }

    @Test
    fun `classifies a genuine shear as GENERAL_AFFINE_UNSUPPORTED`() {
        val matrix = SensorToBufferMatrix3(2.0, 1.0, 0.0, 1.0, 2.0, 0.0, 0.0, 0.0, 1.0)
        assertEquals(SensorToBufferTransformClass.GENERAL_AFFINE_UNSUPPORTED, classifySensorToBufferMatrix(matrix))
    }

    @Test
    fun `classifies a non-zero perspective row as PROJECTIVE_UNSUPPORTED`() {
        val matrix = SensorToBufferMatrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.001, 0.0, 1.0)
        assertEquals(SensorToBufferTransformClass.PROJECTIVE_UNSUPPORTED, classifySensorToBufferMatrix(matrix))
    }

    @Test
    fun `classifies a singular matrix as SINGULAR`() {
        val matrix = SensorToBufferMatrix3(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0)
        assertEquals(SensorToBufferTransformClass.SINGULAR, classifySensorToBufferMatrix(matrix))
    }

    // --- ActiveArrayRect / toActiveArrayRect ---

    @Test
    fun `toActiveArrayRect inverts a centered-crop-then-scale transform`() {
        // Active array region (1008,756)-(3024,2268) - a centered 2016x1512 crop of a 4032x3024
        // active array - mapped onto a 1008x756 buffer (scale 0.5).
        val transform = axisAlignedMatrix(0.5, 0.5, -504.0, -378.0)

        val region = transform.toActiveArrayRect(bufferWidthPx = 1008, bufferHeightPx = 756)

        assertEquals(1008.0, region.leftPx, EPS)
        assertEquals(756.0, region.topPx, EPS)
        assertEquals(3024.0, region.rightPx, EPS)
        assertEquals(2268.0, region.bottomPx, EPS)
    }

    @Test
    fun `toActiveArrayRect is exact for a 90-degree axis permutation`() {
        // W=4032 active array, mapped 90-degrees onto a 3024x4032 buffer (no crop, no extra scale
        // beyond the axis swap itself): bufferX = activeY, bufferY = 4032 - activeX.
        val matrix = SensorToBufferMatrix3(0.0, 1.0, 0.0, -1.0, 0.0, 4032.0, 0.0, 0.0, 1.0)
        assertEquals(SensorToBufferTransformClass.ORTHOGONAL_90, classifySensorToBufferMatrix(matrix))

        val region = matrix.toActiveArrayRect(bufferWidthPx = 3024, bufferHeightPx = 4032)

        // Active array is 4032 wide x 3024 tall; the 90-degree swap maps it exactly onto the
        // 3024x4032 buffer with no crop, so inverting recovers that full active-array rectangle.
        assertEquals(0.0, region.leftPx, EPS)
        assertEquals(0.0, region.topPx, EPS)
        assertEquals(4032.0, region.rightPx, EPS)
        assertEquals(3024.0, region.bottomPx, EPS)
    }

    @Test
    fun `toActiveArrayRect rejects non-positive buffer dimensions`() {
        val transform = axisAlignedMatrix(1.0, 1.0, 0.0, 0.0)
        assertFailsWith<IllegalArgumentException> { transform.toActiveArrayRect(0, 100) }
        assertFailsWith<IllegalArgumentException> { transform.toActiveArrayRect(100, -1) }
    }

    @Test
    fun `toActiveArrayRect rejects a singular matrix`() {
        val transform = axisAlignedMatrix(0.0, 0.0, 0.0, 0.0)
        assertFailsWith<IllegalArgumentException> { transform.toActiveArrayRect(100, 100) }
    }

    @Test
    fun `ActiveArrayRect rejects a degenerate or unordered region`() {
        assertFailsWith<IllegalArgumentException> { ActiveArrayRect(100.0, 0.0, 100.0, 100.0) }
        assertFailsWith<IllegalArgumentException> { ActiveArrayRect(0.0, 100.0, 100.0, 100.0) }
        assertFailsWith<IllegalArgumentException> { ActiveArrayRect(100.0, 0.0, 0.0, 100.0) }
    }

    @Test
    fun `ActiveArrayRect computes width and height`() {
        val region = ActiveArrayRect(100.0, 200.0, 500.0, 800.0)
        assertEquals(400.0, region.widthPx)
        assertEquals(600.0, region.heightPx)
    }

    // --- mapActiveArrayIntrinsicsThroughMatrix: AXIS_ALIGNED_0 (matches the old crop/scale math) ---

    private fun mappedValues(result: MatrixIntrinsicsMappingResult): AnalysisBufferIntrinsicsValues {
        check(result is MatrixIntrinsicsMappingResult.Mapped) { "expected Mapped, was $result" }
        return result.values
    }

    @Test
    fun `no crop, same buffer size is the identity mapping`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 960.0, cyPx = 540.0, widthPx = 1920, heightPx = 1080)
        val matrix = axisAlignedMatrix(1.0, 1.0, 0.0, 0.0)

        val result = mappedValues(mapActiveArrayIntrinsicsThroughMatrix(active, matrix, bufferWidthPx = 1920, bufferHeightPx = 1080))

        assertEquals(1000.0, result.fxPx, EPS)
        assertEquals(1000.0, result.fyPx, EPS)
        assertEquals(960.0, result.cxPx, EPS)
        assertEquals(540.0, result.cyPx, EPS)
        assertFalse(result.axisSwapped)
        assertFalse(result.negateXInput)
        assertFalse(result.negateYInput)
    }

    @Test
    fun `centered crop keeps the principal point centered when buffer size matches the crop`() {
        val active = ActiveArrayIntrinsics(fxPx = 2000.0, fyPx = 2000.0, cxPx = 2016.0, cyPx = 1512.0, widthPx = 4032, heightPx = 3024)
        // centered 2016x1512 crop -> sx=sy=1, translate = -cropLeft/-cropTop
        val matrix = axisAlignedMatrix(1.0, 1.0, -1008.0, -756.0)

        val result = mappedValues(mapActiveArrayIntrinsicsThroughMatrix(active, matrix, bufferWidthPx = 2016, bufferHeightPx = 1512))

        assertEquals(2000.0, result.fxPx, EPS)
        assertEquals(2000.0, result.fyPx, EPS)
        assertEquals(1008.0, result.cxPx, EPS) // exactly bufferWidth/2 - still centered
        assertEquals(756.0, result.cyPx, EPS) // exactly bufferHeight/2
    }

    @Test
    fun `asymmetric crop translates the principal point off-center`() {
        val active = ActiveArrayIntrinsics(fxPx = 2000.0, fyPx = 2000.0, cxPx = 2016.0, cyPx = 1512.0, widthPx = 4032, heightPx = 3024)
        // Same 2016x1512 crop size as above, shifted toward the top-left: crop = (500,300)-(2516,1812).
        val matrix = axisAlignedMatrix(1.0, 1.0, -500.0, -300.0)

        val result = mappedValues(mapActiveArrayIntrinsicsThroughMatrix(active, matrix, bufferWidthPx = 2016, bufferHeightPx = 1512))

        assertEquals(1516.0, result.cxPx, EPS) // 2016 - 500, sx == 1
        assertEquals(1212.0, result.cyPx, EPS) // 1512 - 300, sy == 1
        assertEquals(508.0, result.cxPx - 1008.0, EPS)
    }

    @Test
    fun `non-uniform scale applies distinct sx and sy to fx, fy, cx, cy`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 500.0, cyPx = 500.0, widthPx = 1000, heightPx = 1000)
        val matrix = axisAlignedMatrix(0.5, 0.25, 0.0, 0.0)

        val result = mappedValues(mapActiveArrayIntrinsicsThroughMatrix(active, matrix, bufferWidthPx = 500, bufferHeightPx = 250))

        assertEquals(500.0, result.fxPx, EPS)
        assertEquals(250.0, result.fyPx, EPS)
        assertEquals(250.0, result.cxPx, EPS)
        assertEquals(125.0, result.cyPx, EPS)
    }

    @Test
    fun `portrait active array and portrait buffer dimensions are handled without swapping`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 1512.0, cyPx = 2016.0, widthPx = 3024, heightPx = 4032)
        val matrix = axisAlignedMatrix(0.25, 0.25, 0.0, 0.0)

        val result = mappedValues(mapActiveArrayIntrinsicsThroughMatrix(active, matrix, bufferWidthPx = 756, bufferHeightPx = 1008))

        assertEquals(250.0, result.fxPx, EPS)
        assertEquals(250.0, result.fyPx, EPS)
        assertEquals(378.0, result.cxPx, EPS)
        assertEquals(504.0, result.cyPx, EPS)
        assertEquals(756, result.widthPx)
        assertEquals(1008, result.heightPx)
    }

    @Test
    fun `invalid buffer dimensions are rejected`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 500.0, cyPx = 500.0, widthPx = 1000, heightPx = 1000)
        val matrix = axisAlignedMatrix(0.5, 0.5, 0.0, 0.0)

        assertFailsWith<IllegalArgumentException> {
            mapActiveArrayIntrinsicsThroughMatrix(active, matrix, bufferWidthPx = 0, bufferHeightPx = 500)
        }
        assertFailsWith<IllegalArgumentException> {
            mapActiveArrayIntrinsicsThroughMatrix(active, matrix, bufferWidthPx = 500, bufferHeightPx = -1)
        }
    }

    @Test
    fun `source geometry (active intrinsics) remains unchanged after mapping`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 500.0, cyPx = 500.0, widthPx = 1000, heightPx = 1000)
        val matrix = axisAlignedMatrix(0.5, 0.5, 0.0, 0.0)
        val activeBefore = active.copy()

        mapActiveArrayIntrinsicsThroughMatrix(active, matrix, bufferWidthPx = 500, bufferHeightPx = 500)

        assertEquals(activeBefore, active)
    }

    // --- mapActiveArrayIntrinsicsThroughMatrix: axis swap (90/270) ---

    @Test
    fun `a 90-degree axis permutation swaps fx and fy and reports axisSwapped`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1500.0, cxPx = 2016.0, cyPx = 1512.0, widthPx = 4032, heightPx = 3024)
        // bufferX = activeY, bufferY = 4032 - activeX (no extra crop/scale).
        val matrix = SensorToBufferMatrix3(0.0, 1.0, 0.0, -1.0, 0.0, 4032.0, 0.0, 0.0, 1.0)

        val mapping = mapActiveArrayIntrinsicsThroughMatrix(active, matrix, bufferWidthPx = 3024, bufferHeightPx = 4032)
        check(mapping is MatrixIntrinsicsMappingResult.Mapped)
        assertEquals(SensorToBufferTransformClass.ORTHOGONAL_90, mapping.transformClass)
        val result = mapping.values

        assertTrue(result.axisSwapped)
        assertEquals(1500.0, result.fxPx, EPS) // buffer-X now driven by the original fy
        assertEquals(1000.0, result.fyPx, EPS) // buffer-Y now driven by the original fx
        assertEquals(1512.0, result.cxPx, EPS) // cy carries straight through as bufferX's constant
        assertEquals(4032.0 - 2016.0, result.cyPx, EPS)
        assertTrue(result.negateYInput) // m10 < 0 for this specific 90-degree matrix
        assertFalse(result.negateXInput)
    }

    @Test
    fun `a 180-degree rotation sign-normalizes both focal lengths and reports both negate flags`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 2016.0, cyPx = 1512.0, widthPx = 4032, heightPx = 3024)
        val matrix = SensorToBufferMatrix3(-1.0, 0.0, 4032.0, 0.0, -1.0, 3024.0, 0.0, 0.0, 1.0)

        val mapping = mapActiveArrayIntrinsicsThroughMatrix(active, matrix, bufferWidthPx = 4032, bufferHeightPx = 3024)
        check(mapping is MatrixIntrinsicsMappingResult.Mapped)
        assertEquals(SensorToBufferTransformClass.ORTHOGONAL_180, mapping.transformClass)
        val result = mapping.values

        assertFalse(result.axisSwapped)
        assertTrue(result.negateXInput)
        assertTrue(result.negateYInput)
        assertEquals(1000.0, result.fxPx, EPS)
        assertEquals(1000.0, result.fyPx, EPS)
        assertEquals(2016.0, result.cxPx, EPS)
        assertEquals(1512.0, result.cyPx, EPS)
    }

    @Test
    fun `unsupported transform classes return Unsupported, never a guessed mapping`() {
        val active = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 500.0, cyPx = 500.0, widthPx = 1000, heightPx = 1000)

        val mirrored = SensorToBufferMatrix3(-1.0, 0.0, 1000.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        val mirroredResult = mapActiveArrayIntrinsicsThroughMatrix(active, mirrored, bufferWidthPx = 1000, bufferHeightPx = 1000)
        assertEquals(
            MatrixIntrinsicsMappingResult.Unsupported(SensorToBufferTransformClass.MIRRORED),
            mirroredResult,
        )

        val projective = SensorToBufferMatrix3(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.01, 0.0, 1.0)
        val projectiveResult = mapActiveArrayIntrinsicsThroughMatrix(active, projective, bufferWidthPx = 1000, bufferHeightPx = 1000)
        assertEquals(
            MatrixIntrinsicsMappingResult.Unsupported(SensorToBufferTransformClass.PROJECTIVE_UNSUPPORTED),
            projectiveResult,
        )
    }

    // --- skew propagation ---

    @Test
    fun `non-zero active-array skew never leaks into fx, fy, cx, or cy for AXIS_ALIGNED_0`() {
        val withSkew = ActiveArrayIntrinsics(fxPx = 1000.0, fyPx = 1000.0, cxPx = 500.0, cyPx = 500.0, widthPx = 1000, heightPx = 1000, skewPx = 12.0)
        val withoutSkew = withSkew.copy(skewPx = 0.0)
        val matrix = axisAlignedMatrix(0.5, 0.5, 0.0, 0.0)

        val withSkewValues = mappedValues(mapActiveArrayIntrinsicsThroughMatrix(withSkew, matrix, bufferWidthPx = 500, bufferHeightPx = 500))
        val withoutSkewValues = mappedValues(mapActiveArrayIntrinsicsThroughMatrix(withoutSkew, matrix, bufferWidthPx = 500, bufferHeightPx = 500))

        assertEquals(withoutSkewValues.fxPx, withSkewValues.fxPx, EPS)
        assertEquals(withoutSkewValues.fyPx, withSkewValues.fyPx, EPS)
        assertEquals(withoutSkewValues.cxPx, withSkewValues.cxPx, EPS)
        assertEquals(withoutSkewValues.cyPx, withSkewValues.cyPx, EPS)
        assertEquals(6.0, withSkewValues.skewPx, EPS) // m00 (0.5) * skewActive (12.0)
    }

    // --- toCameraIntrinsics ---

    @Test
    fun `toCameraIntrinsics publishes CAMERA_CHARACTERISTICS source and AnalysisBuffer reference`() {
        val values = AnalysisBufferIntrinsicsValues(fxPx = 500.0, fyPx = 500.0, cxPx = 320.0, cyPx = 240.0, widthPx = 640, heightPx = 480)

        val intrinsics =
            values.toCameraIntrinsics(
                focalLengthMm = 4.25,
                sensorWidthMm = 5.76,
                sensorHeightMm = 4.29,
                quality = CameraIntrinsicsQuality.CALIBRATED,
            )

        assertEquals(CameraIntrinsicsSource.CAMERA_CHARACTERISTICS, intrinsics.source)
        assertEquals(CameraIntrinsicsReference.AnalysisBuffer(640, 480), intrinsics.reference)
        assertEquals(CameraIntrinsicsQuality.CALIBRATED, intrinsics.quality)
        assertEquals(320.0, intrinsics.principalPointXPx)
        assertEquals(240.0, intrinsics.principalPointYPx)
        assertEquals(4.25, intrinsics.focalLengthMm)
        assertFalse(intrinsics.axisSwapped)
    }

    @Test
    fun `toCameraIntrinsics carries axisSwapped and negate flags through`() {
        val values =
            AnalysisBufferIntrinsicsValues(
                fxPx = 500.0, fyPx = 400.0, cxPx = 320.0, cyPx = 240.0, widthPx = 640, heightPx = 480,
                axisSwapped = true, negateXInput = false, negateYInput = true,
            )

        val intrinsics =
            values.toCameraIntrinsics(
                focalLengthMm = 4.25,
                sensorWidthMm = 5.76,
                sensorHeightMm = 4.29,
                quality = CameraIntrinsicsQuality.CALIBRATED,
            )

        assertTrue(intrinsics.axisSwapped)
        assertFalse(intrinsics.negateXInput)
        assertTrue(intrinsics.negateYInput)
    }

    @Test
    fun `toCameraIntrinsics FOV round-trips back to the exact original fx and fy`() {
        val values = AnalysisBufferIntrinsicsValues(fxPx = 533.333, fyPx = 711.111, cxPx = 320.0, cyPx = 240.0, widthPx = 640, heightPx = 480)

        val intrinsics =
            values.toCameraIntrinsics(
                focalLengthMm = 4.25,
                sensorWidthMm = 5.76,
                sensorHeightMm = 4.29,
                quality = CameraIntrinsicsQuality.APPROXIMATE_PRINCIPAL_POINT,
            )

        val recoveredFx = values.widthPx / (2.0 * tan(Math.toRadians(intrinsics.horizontalFovDeg) / 2.0))
        val recoveredFy = values.heightPx / (2.0 * tan(Math.toRadians(intrinsics.verticalFovDeg) / 2.0))
        assertEquals(values.fxPx, recoveredFx, EPS)
        assertEquals(values.fyPx, recoveredFy, EPS)
    }
}
