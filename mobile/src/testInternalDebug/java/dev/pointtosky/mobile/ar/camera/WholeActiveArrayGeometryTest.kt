package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.SensorToBufferMatrix3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `internalDebug`-only pure JVM tests for [assessWholeActiveArrayGeometry] — the hypothesis-scoped
 * geometry classifier added alongside (never replacing) [assessWholeActiveArrayMappingHypothesis]'s
 * binary verdict. Fixture 1 is the real Pixel 9 CameraX 1.4.2 matrix from
 * `docs/recon/cam_2c_sensor_to_buffer_domain_recon.md` §4; fixture 2 is the same device's historical
 * CameraX 1.3.4 identity matrix.
 */
class WholeActiveArrayGeometryTest {
    private fun axisAligned(
        scaleX: Double,
        scaleY: Double,
        translateX: Double = 0.0,
        translateY: Double = 0.0,
    ) = SensorToBufferMatrix3(
        m00 = scaleX, m01 = 0.0, m02 = translateX,
        m10 = 0.0, m11 = scaleY, m12 = translateY,
        m20 = 0.0, m21 = 0.0, m22 = 1.0,
    )

    /** The real Pixel 9 CameraX 1.4.2 matrix (widened float32). */
    private val pixel9Matrix = axisAligned(0.1568627506494522, 0.1568627506494522, 0.0, -0.9411764740943909)

    private fun assess(
        matrix: SensorToBufferMatrix3,
        sourceLeft: Int? = 0,
        sourceTop: Int? = 0,
        sourceWidth: Int? = 4080,
        sourceHeight: Int? = 3072,
        bufferWidth: Int? = 640,
        bufferHeight: Int? = 480,
    ) = assessWholeActiveArrayGeometry(
        matrix = matrix,
        sourceLeftPx = sourceLeft,
        sourceTopPx = sourceTop,
        sourceWidthPx = sourceWidth,
        sourceHeightPx = sourceHeight,
        bufferWidthPx = bufferWidth,
        bufferHeightPx = bufferHeight,
    )

    @Test
    fun `fixture 1 - real Pixel 9 CameraX 142 matrix classifies as uniform-scale center crop`() {
        val result = assess(pixel9Matrix)

        assertEquals(WholeActiveArrayGeometryClass.UNIFORM_SCALE_CENTER_CROP, result.geometryClass)
        // Vertical overflow ~0.941176 px per side; horizontal fits.
        assertEquals(0.9411764740943909, result.overflowTopPx!!, 1e-4)
        assertEquals(0.9411764705882353, result.overflowBottomPx!!, 1e-4)
        assertTrue(abs(result.overflowLeftPx!!) <= DEFAULT_FLOAT_NOISE_TOLERANCE_PX)
        assertTrue(abs(result.overflowRightPx!!) <= DEFAULT_FLOAT_NOISE_TOLERANCE_PX)
        // Center and symmetry residuals are float-storage noise, orders below the geometric crop.
        assertTrue(abs(result.centerResidualXPx!!) <= DEFAULT_FLOAT_NOISE_TOLERANCE_PX)
        assertTrue(abs(result.centerResidualYPx!!) <= DEFAULT_FLOAT_NOISE_TOLERANCE_PX)
        assertTrue(result.verticalSymmetryResidualPx!! <= DEFAULT_FLOAT_NOISE_TOLERANCE_PX)
        assertEquals(0.0, result.isotropyResidual!!, 0.0)
    }

    @Test
    fun `fixture 2 - historical CameraX 134 identity matrix does not classify as the intended center-crop construction`() {
        val result = assess(axisAligned(1.0, 1.0))

        // Identity over 4080x3072 vs 640x480 overflows enormously on BOTH axes — not the traced
        // construction's shape; reported honestly as UNEXPLAINED, never rounded to a crop class.
        assertEquals(WholeActiveArrayGeometryClass.UNEXPLAINED, result.geometryClass)
        assertEquals(3440.0, result.overflowRightPx!!, 1e-9)
        assertEquals(2592.0, result.overflowBottomPx!!, 1e-9)
    }

    @Test
    fun `fixture 3 - exact aspect match classifies as exact bounds fit despite float32 scale error`() {
        // 4000x3000 -> 640x480 (both 4:3): the float32 scale 0.16f widens with ~2.4e-9 relative
        // error, mapping the right edge to 640.0000095 — within float-noise, never a "crop".
        val scale = (0.16f).toDouble()
        val result = assess(axisAligned(scale, scale), sourceWidth = 4000, sourceHeight = 3000)

        assertEquals(WholeActiveArrayGeometryClass.EXACT_BOUNDS_FIT, result.geometryClass)
    }

    @Test
    fun `fixture 4 - 1280x720 buffer classifies as center crop with about 121,88 px per side`() {
        val predicted = predictCameraX142SensorToBufferMatrix(
            CameraBasisRect(0, 0, 4080, 3072),
            bufferWidthPx = 1280,
            bufferHeightPx = 720,
        )!!
        val result = assess(predicted.matrix, bufferWidth = 1280, bufferHeight = 720)

        assertEquals(WholeActiveArrayGeometryClass.UNIFORM_SCALE_CENTER_CROP, result.geometryClass)
        assertEquals(121.88235294117646, result.overflowTopPx!!, 1e-3)
        assertEquals(121.88235294117646, result.overflowBottomPx!!, 1e-3)
        assertTrue(abs(result.overflowLeftPx!!) <= DEFAULT_FLOAT_NOISE_TOLERANCE_PX)
    }

    @Test
    fun `fixture 5 - source wider than buffer classifies as center crop with horizontal overflow`() {
        // 4000x2000 (2 to 1) -> 640x480: height fits, width overflows 160 px per side.
        val predicted = predictCameraX142SensorToBufferMatrix(CameraBasisRect(0, 0, 4000, 2000), 640, 480)!!
        val result = assess(predicted.matrix, sourceWidth = 4000, sourceHeight = 2000)

        assertEquals(WholeActiveArrayGeometryClass.UNIFORM_SCALE_CENTER_CROP, result.geometryClass)
        assertEquals(160.0, result.overflowLeftPx!!, 1e-3)
        assertEquals(160.0, result.overflowRightPx!!, 1e-3)
        assertTrue(abs(result.overflowTopPx!!) <= DEFAULT_FLOAT_NOISE_TOLERANCE_PX)
        assertTrue(abs(result.overflowBottomPx!!) <= DEFAULT_FLOAT_NOISE_TOLERANCE_PX)
    }

    @Test
    fun `fixture 6 - uniform letterbox classifies as letterbox, not crop`() {
        // s = 0.15625 fits the height exactly; width maps to 637.5, centered 1.25 px inside each side.
        val result = assess(axisAligned(0.15625, 0.15625, 1.25, 0.0))

        assertEquals(WholeActiveArrayGeometryClass.UNIFORM_SCALE_LETTERBOX, result.geometryClass)
        assertEquals(-1.25, result.overflowLeftPx!!, 1e-9)
        assertEquals(-1.25, result.overflowRightPx!!, 1e-9)
    }

    @Test
    fun `fixture 7 - asymmetric crop is distinguished from center crop`() {
        // Same uniform scale as the Pixel 9 fixture but ALL vertical excess cropped from the top.
        val result = assess(axisAligned(0.1568627506494522, 0.1568627506494522, 0.0, -1.8823529481887817))

        assertEquals(WholeActiveArrayGeometryClass.UNIFORM_SCALE_ASYMMETRIC_CROP, result.geometryClass)
        assertEquals(1.8823529481887817, result.overflowTopPx!!, 1e-4)
        assertTrue(abs(result.overflowBottomPx!!) <= DEFAULT_GEOMETRIC_MAGNITUDE_TOLERANCE_PX)
        assertTrue(result.verticalSymmetryResidualPx!! > DEFAULT_FLOAT_NOISE_TOLERANCE_PX)
    }

    @Test
    fun `fixture 8 - non-uniform scale is rejected before any exact-fit claim`() {
        // Anisotropic stretch-to-fill: maps bounds-to-bounds exactly, but is NOT the traced
        // construction — isotropy is checked before the exact-fit class.
        val result = assess(axisAligned(640.0 / 4080.0, 480.0 / 3072.0))

        assertEquals(WholeActiveArrayGeometryClass.NON_UNIFORM_SCALE, result.geometryClass)
        assertTrue(result.isotropyResidual!! > DEFAULT_SCALE_ISOTROPY_RELATIVE_TOLERANCE)
    }

    @Test
    fun `fixture 9 - shear is rejected as shear-or-perspective`() {
        val sheared = SensorToBufferMatrix3(0.15, 0.01, 0.0, 0.0, 0.15, 0.0, 0.0, 0.0, 1.0)
        assertEquals(WholeActiveArrayGeometryClass.SHEAR_OR_PERSPECTIVE, assess(sheared).geometryClass)
    }

    @Test
    fun `fixture 10 - a non-affine bottom row is rejected as shear-or-perspective`() {
        val projective = SensorToBufferMatrix3(0.15, 0.0, 0.0, 0.0, 0.15, 0.0, 0.001, 0.0, 1.0)
        assertEquals(WholeActiveArrayGeometryClass.SHEAR_OR_PERSPECTIVE, assess(projective).geometryClass)
    }

    @Test
    fun `fixture 11 - a singular matrix is degenerate`() {
        val singular = axisAligned(0.0, 0.15)
        val result = assess(singular)
        assertEquals(WholeActiveArrayGeometryClass.DEGENERATE, result.geometryClass)
        assertNull(result.mappedBoundsPx)
    }

    @Test
    fun `fixture 12 - missing or non-positive dimensions are insufficient input`() {
        assertEquals(
            WholeActiveArrayGeometryClass.INSUFFICIENT_INPUT,
            assess(pixel9Matrix, sourceWidth = null).geometryClass,
        )
        assertEquals(
            WholeActiveArrayGeometryClass.INSUFFICIENT_INPUT,
            assess(pixel9Matrix, sourceHeight = 0).geometryClass,
        )
        assertEquals(
            WholeActiveArrayGeometryClass.INSUFFICIENT_INPUT,
            assess(pixel9Matrix, bufferWidth = -1).geometryClass,
        )
        assertEquals(
            WholeActiveArrayGeometryClass.INSUFFICIENT_INPUT,
            assess(pixel9Matrix, bufferHeight = null).geometryClass,
        )
    }

    @Test
    fun `fixture 13 - non-zero active-array offsets assess against the native rect position`() {
        // The model's prediction for an offset rect must classify identically: its translation
        // removes the native offsets, so the mapped bounds land on the buffer the same way.
        val predicted = predictCameraX142SensorToBufferMatrix(CameraBasisRect(8, 8, 4088, 3080), 640, 480)!!
        val result = assess(predicted.matrix, sourceLeft = 8, sourceTop = 8)

        assertEquals(WholeActiveArrayGeometryClass.UNIFORM_SCALE_CENTER_CROP, result.geometryClass)
        assertEquals(0.9411764705882353, result.overflowTopPx!!, 1e-3)
    }

    @Test
    fun `fixture 14 - float-noise boundary separates storage noise from geometric crop`() {
        // (a) A sub-noise translation stays EXACT_BOUNDS_FIT.
        val scale = (0.16f).toDouble()
        val nudged = axisAligned(scale, scale, 0.0, 5e-4)
        assertEquals(
            WholeActiveArrayGeometryClass.EXACT_BOUNDS_FIT,
            assess(nudged, sourceWidth = 4000, sourceHeight = 3000).geometryClass,
        )
        // (b) A grey-zone translation (between float noise and geometric magnitude) is UNEXPLAINED —
        // neither silently absorbed into "fit" nor promoted to "crop".
        val greyZone = axisAligned(scale, scale, 0.0, 5e-3)
        assertEquals(
            WholeActiveArrayGeometryClass.UNEXPLAINED,
            assess(greyZone, sourceWidth = 4000, sourceHeight = 3000).geometryClass,
        )
        // (c) The real 0.941 px crop is never swallowed by the float tolerance.
        assertEquals(WholeActiveArrayGeometryClass.UNIFORM_SCALE_CENTER_CROP, assess(pixel9Matrix).geometryClass)
    }

    @Test
    fun `the assessment carries the full residual set for export`() {
        val result = assess(pixel9Matrix)
        assertNotNull(result.mappedBoundsPx)
        assertNotNull(result.mappedCenterXPx)
        assertNotNull(result.scaleX)
        assertNotNull(result.translationY)
        assertNotNull(result.horizontalSymmetryResidualPx)
        assertTrue(result.reason.isNotBlank())
    }
}
