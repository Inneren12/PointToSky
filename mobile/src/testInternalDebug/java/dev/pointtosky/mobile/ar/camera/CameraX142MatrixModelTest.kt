package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `internalDebug`-only pure JVM tests for [predictCameraX142SensorToBufferMatrix] — the
 * CAMERAX_1_4_2_IMPLEMENTATION_MODEL (recon `docs/recon/cam_2c_sensor_to_buffer_domain_recon.md`
 * §2.1/§4), never an eternal cross-version API guarantee. The bit-level fixture values below are the
 * real Pixel 9 CameraX 1.4.2 observation from that recon.
 */
class CameraX142MatrixModelTest {
    private fun rect(
        left: Int = 0,
        top: Int = 0,
        width: Int,
        height: Int,
    ) = CameraBasisRect(leftPx = left, topPx = top, rightPx = left + width, bottomPx = top + height)

    @Test
    fun `real Pixel 9 fixture - 4080x3072 to 640x480 reproduces the observed float32 coefficients bit for bit`() {
        val predicted = predictCameraX142SensorToBufferMatrix(rect(width = 4080, height = 3072), 640, 480)!!

        // The device-observed values (widened android.graphics.Matrix float32): m00 = m11 =
        // double(float32(640/4080)); the recon's §4 derivation for the translation. The scale must be
        // bit-exact; the translation is asserted bit-exact against this model's own -t/s sequencing
        // (the platform may differ by a few ULPs — see the model's KDoc — which the model-match
        // tolerance, not this fixture, absorbs).
        assertEquals(0.1568627506494522, predicted.matrix.m00, 0.0)
        assertEquals(0.1568627506494522, predicted.matrix.m11, 0.0)
        assertEquals(-0.9411764740943909, predicted.matrix.m12, 1e-9)
        assertEquals(0.0, predicted.matrix.m02, 1e-9)
        assertEquals(0.0, predicted.matrix.m01, 0.0)
        assertEquals(0.0, predicted.matrix.m10, 0.0)
        assertEquals(CameraX142FitAxis.WIDTH_FITS_EXACTLY, predicted.fitAxis)
        assertEquals(CameraX142OverflowDirection.VERTICAL_TOP_BOTTOM, predicted.overflowDirection)
        assertEquals(0.9411764705882353, predicted.expectedOverflowPerSidePx, 1e-9)
    }

    @Test
    fun `4080x3072 to 1280x720 predicts vertical overflow of about 121,88 px per side`() {
        val predicted = predictCameraX142SensorToBufferMatrix(rect(width = 4080, height = 3072), 1280, 720)!!

        assertEquals(1.0 / 3.1875, predicted.matrix.m00, 1e-7)
        assertEquals(-121.88235294117646, predicted.matrix.m12, 1e-4)
        assertEquals(CameraX142FitAxis.WIDTH_FITS_EXACTLY, predicted.fitAxis)
        assertEquals(CameraX142OverflowDirection.VERTICAL_TOP_BOTTOM, predicted.overflowDirection)
        assertEquals(121.88235294117646, predicted.expectedOverflowPerSidePx, 1e-9)
    }

    @Test
    fun `exact aspect match predicts both axes fitting with no overflow`() {
        val predicted = predictCameraX142SensorToBufferMatrix(rect(width = 4000, height = 3000), 640, 480)!!

        assertEquals(CameraX142FitAxis.BOTH_FIT_EXACTLY, predicted.fitAxis)
        assertEquals(CameraX142OverflowDirection.NONE, predicted.overflowDirection)
        assertEquals(0.0, predicted.expectedOverflowPerSidePx, 0.0)
        assertEquals(0.16, predicted.matrix.m00, 1e-7)
        assertEquals(0.0, predicted.matrix.m12, 1e-6)
    }

    @Test
    fun `source wider than buffer aspect predicts horizontal left-right overflow`() {
        // 2:1 source vs 4:3 buffer: height fits, width overflows left/right.
        val predicted = predictCameraX142SensorToBufferMatrix(rect(width = 4000, height = 2000), 640, 480)!!

        assertEquals(CameraX142FitAxis.HEIGHT_FITS_EXACTLY, predicted.fitAxis)
        assertEquals(CameraX142OverflowDirection.HORIZONTAL_LEFT_RIGHT, predicted.overflowDirection)
        assertEquals(160.0, predicted.expectedOverflowPerSidePx, 1e-9)
        assertEquals(0.24, predicted.matrix.m00, 1e-6)
        assertEquals(-160.0, predicted.matrix.m02, 1e-3)
        // The float32 fit scale (4.1666665f) does not multiply back to exactly 2000, leaving a
        // ~1.5e-5 px translation — float-storage noise, well inside the model-match tolerance.
        assertEquals(0.0, predicted.matrix.m12, 1e-4)
    }

    @Test
    fun `non-zero source rect offsets are removed by the predicted translation`() {
        // Same 4080x3072 active array, but positioned at (8, 8) in the pixel array — the model must
        // consume the native rect exactly as `new RectF(fullSensorRect)` would (recon §2.2), so the
        // rect's own top-left maps to the same buffer point the offset-free rect's top-left does.
        val offsetPredicted = predictCameraX142SensorToBufferMatrix(rect(left = 8, top = 8, width = 4080, height = 3072), 640, 480)!!
        val zeroPredicted = predictCameraX142SensorToBufferMatrix(rect(width = 4080, height = 3072), 640, 480)!!

        val m = offsetPredicted.matrix
        val mappedLeftTopX = m.m00 * 8.0 + m.m02
        val mappedLeftTopY = m.m11 * 8.0 + m.m12
        val z = zeroPredicted.matrix
        assertEquals(z.m02, mappedLeftTopX, 1e-4)
        assertEquals(z.m12, mappedLeftTopY, 1e-4)
        // The scale is offset-independent.
        assertEquals(z.m00, m.m00, 0.0)
        // And the translation itself now removes the native offsets.
        assertEquals(-(8.0 + 0.0) / 6.375, m.m02, 1e-4)
        assertEquals(-(8.0 + 6.0) / 6.375, m.m12, 1e-4)
    }

    @Test
    fun `non-positive buffer dimensions yield a typed null, never a guess`() {
        assertNull(predictCameraX142SensorToBufferMatrix(rect(width = 4080, height = 3072), 0, 480))
        assertNull(predictCameraX142SensorToBufferMatrix(rect(width = 4080, height = 3072), 640, -1))
    }

    @Test
    fun `the predicted matrix is always axis-aligned affine`() {
        val predicted = predictCameraX142SensorToBufferMatrix(rect(width = 4080, height = 3072), 640, 480)!!
        val m = predicted.matrix
        assertTrue(m.m01 == 0.0 && m.m10 == 0.0 && m.m20 == 0.0 && m.m21 == 0.0 && m.m22 == 1.0)
    }
}
