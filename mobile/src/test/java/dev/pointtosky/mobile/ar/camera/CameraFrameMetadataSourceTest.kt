package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.SensorToBufferTransform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pure JVM tests for [toCameraFrameMetadata] (CAM-1c) — the extraction seam that maps raw
 * [CloseableFrameMetadataSource] fields onto a validated [dev.pointtosky.core.astro.projection.camera.CameraFrameMetadata].
 * Uses [FakeFrameMetadataSource] exclusively; no real `ImageProxy`, no camera, no plane access.
 */
class CameraFrameMetadataSourceTest {
    @Test
    fun `1920x1080 rotation 0 maps straight through without swapping dimensions`() {
        val metadata =
            FakeFrameMetadataSource(widthPx = 1920, heightPx = 1080, rotationDegrees = 0).toCameraFrameMetadata()
        assertEquals(1920, metadata.bufferWidthPx)
        assertEquals(1080, metadata.bufferHeightPx)
        assertEquals(0, metadata.rotationDegrees)
    }

    @Test
    fun `1080x1920 rotation 90 preserves the portrait buffer dimensions unswapped`() {
        val metadata =
            FakeFrameMetadataSource(widthPx = 1080, heightPx = 1920, rotationDegrees = 90).toCameraFrameMetadata()
        assertEquals(1080, metadata.bufferWidthPx)
        assertEquals(1920, metadata.bufferHeightPx)
        assertEquals(90, metadata.rotationDegrees)
    }

    @Test
    fun `all four legal rotations map through unchanged`() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val metadata = FakeFrameMetadataSource(rotationDegrees = rotation).toCameraFrameMetadata()
            assertEquals(rotation, metadata.rotationDegrees)
        }
    }

    @Test
    fun `timestamp is preserved exactly`() {
        listOf(0L, 1L, 123_456_789_012L, Long.MAX_VALUE).forEach { ts ->
            val metadata = FakeFrameMetadataSource(timestampNanos = ts).toCameraFrameMetadata()
            assertEquals(ts, metadata.timestampNanos)
        }
    }

    @Test
    fun `crop rect fields map through when present`() {
        val metadata =
            FakeFrameMetadataSource(
                widthPx = 1920,
                heightPx = 1080,
                cropRectLeftPx = 10,
                cropRectTopPx = 20,
                cropRectRightPx = 1900,
                cropRectBottomPx = 1060,
            ).toCameraFrameMetadata()
        assertEquals(10, metadata.cropRectLeftPx)
        assertEquals(20, metadata.cropRectTopPx)
        assertEquals(1900, metadata.cropRectRightPx)
        assertEquals(1060, metadata.cropRectBottomPx)
    }

    @Test
    fun `crop rect fields are null when the source has none`() {
        val metadata = FakeFrameMetadataSource().toCameraFrameMetadata()
        assertNull(metadata.cropRectLeftPx)
        assertNull(metadata.cropRectTopPx)
        assertNull(metadata.cropRectRightPx)
        assertNull(metadata.cropRectBottomPx)
    }

    @Test
    fun `invalid raw values propagate CameraFrameMetadata validation failures through the seam`() {
        assertFailsWith<IllegalArgumentException> {
            FakeFrameMetadataSource(widthPx = 0).toCameraFrameMetadata()
        }
        assertFailsWith<IllegalArgumentException> {
            FakeFrameMetadataSource(rotationDegrees = 45).toCameraFrameMetadata()
        }
        assertFailsWith<IllegalArgumentException> {
            FakeFrameMetadataSource(timestampNanos = -1L).toCameraFrameMetadata()
        }
    }

    @Test
    fun `sensorToBufferTransform is null when the source has none`() {
        assertNull(FakeFrameMetadataSource().toCameraFrameMetadata().sensorToBufferTransform)
    }

    @Test
    fun `sensorToBufferTransform maps through when present`() {
        val transform = SensorToBufferTransform(scaleX = 0.5, scaleY = 0.5, translateXPx = -10.0, translateYPx = -20.0)
        val metadata = FakeFrameMetadataSource(sensorToBufferTransform = transform).toCameraFrameMetadata()
        assertEquals(transform, metadata.sensorToBufferTransform)
    }

    // --- sensorToBufferTransformFromMatrixValues (CAM-2c) ---

    /** `Matrix.getValues()` layout for an identity matrix: scaleX=scaleY=1, persp2=1, everything else 0. */
    private fun identityMatrixValues() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private fun scaleTranslateMatrixValues(
        scaleX: Float,
        scaleY: Float,
        translateX: Float,
        translateY: Float,
    ) = floatArrayOf(scaleX, 0f, translateX, 0f, scaleY, translateY, 0f, 0f, 1f)

    @Test
    fun `an identity matrix converts to an identity SensorToBufferTransform`() {
        val transform = assertNotNull(sensorToBufferTransformFromMatrixValues(identityMatrixValues()))
        assertEquals(1.0, transform.scaleX)
        assertEquals(1.0, transform.scaleY)
        assertEquals(0.0, transform.translateXPx)
        assertEquals(0.0, transform.translateYPx)
    }

    @Test
    fun `a pure scale-and-translate matrix converts exactly`() {
        val values = scaleTranslateMatrixValues(scaleX = 0.15873f, scaleY = 0.15873f, translateX = -100f, translateY = -50f)
        val transform = assertNotNull(sensorToBufferTransformFromMatrixValues(values))
        assertEquals(0.15873f.toDouble(), transform.scaleX)
        assertEquals(0.15873f.toDouble(), transform.scaleY)
        assertEquals(-100f.toDouble(), transform.translateXPx)
        assertEquals(-50f.toDouble(), transform.translateYPx)
    }

    @Test
    fun `a non-zero skew component is rejected as null (not axis-aligned)`() {
        val skewedX = floatArrayOf(1f, 0.2f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val skewedY = floatArrayOf(1f, 0f, 0f, 0.2f, 1f, 0f, 0f, 0f, 1f)
        assertNull(sensorToBufferTransformFromMatrixValues(skewedX))
        assertNull(sensorToBufferTransformFromMatrixValues(skewedY))
    }

    @Test
    fun `a non-trivial perspective component is rejected as null (not affine)`() {
        val perspective = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.001f, 0f, 1f)
        assertNull(sensorToBufferTransformFromMatrixValues(perspective))
    }

    @Test
    fun `zero or negative scale is rejected as null`() {
        assertNull(sensorToBufferTransformFromMatrixValues(scaleTranslateMatrixValues(0f, 1f, 0f, 0f)))
        assertNull(sensorToBufferTransformFromMatrixValues(scaleTranslateMatrixValues(1f, -1f, 0f, 0f)))
    }

    @Test
    fun `non-finite scale or translate is rejected as null`() {
        assertNull(sensorToBufferTransformFromMatrixValues(scaleTranslateMatrixValues(Float.NaN, 1f, 0f, 0f)))
        assertNull(sensorToBufferTransformFromMatrixValues(scaleTranslateMatrixValues(1f, 1f, Float.POSITIVE_INFINITY, 0f)))
    }

    @Test
    fun `small skew within tolerance is still accepted (floating-point noise absorption)`() {
        val nearlyAxisAligned = floatArrayOf(1f, 1e-6f, 0f, 1e-6f, 1f, 0f, 0f, 0f, 1f)
        assertNotNull(sensorToBufferTransformFromMatrixValues(nearlyAxisAligned))
    }

    @Test
    fun `values array of the wrong size is rejected`() {
        assertFailsWith<IllegalArgumentException> { sensorToBufferTransformFromMatrixValues(floatArrayOf(1f, 0f, 0f)) }
    }
}
