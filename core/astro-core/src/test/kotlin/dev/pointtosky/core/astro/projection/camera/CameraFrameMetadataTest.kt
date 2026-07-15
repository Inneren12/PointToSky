package dev.pointtosky.core.astro.projection.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Pure JVM tests for [CameraFrameMetadata] (CAM-1c). Covers both the validation contract (invalid
 * values are rejected eagerly, never clamped) and raw-to-model extraction using plain values only —
 * no `ImageProxy`, no CameraX, no Android dependency.
 */
class CameraFrameMetadataTest {
    private fun valid(
        timestampNanos: Long = 123_456_789L,
        bufferWidthPx: Int = 1920,
        bufferHeightPx: Int = 1080,
        rotationDegrees: Int = 0,
        cropRectLeftPx: Int? = null,
        cropRectTopPx: Int? = null,
        cropRectRightPx: Int? = null,
        cropRectBottomPx: Int? = null,
    ) = CameraFrameMetadata(
        timestampNanos = timestampNanos,
        bufferWidthPx = bufferWidthPx,
        bufferHeightPx = bufferHeightPx,
        rotationDegrees = rotationDegrees,
        cropRectLeftPx = cropRectLeftPx,
        cropRectTopPx = cropRectTopPx,
        cropRectRightPx = cropRectRightPx,
        cropRectBottomPx = cropRectBottomPx,
    )

    @Test
    fun `valid metadata constructs successfully`() {
        val metadata = valid()
        assertEquals(123_456_789L, metadata.timestampNanos)
        assertEquals(1920, metadata.bufferWidthPx)
        assertEquals(1080, metadata.bufferHeightPx)
        assertEquals(0, metadata.rotationDegrees)
    }

    @Test
    fun `zero or negative width is rejected`() {
        listOf(0, -1, Int.MIN_VALUE).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("bufferWidthPx=$bad") { valid(bufferWidthPx = bad) }
        }
    }

    @Test
    fun `zero or negative height is rejected`() {
        listOf(0, -1, Int.MIN_VALUE).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("bufferHeightPx=$bad") { valid(bufferHeightPx = bad) }
        }
    }

    @Test
    fun `rotation degrees other than 0, 90, 180, or 270 are rejected`() {
        listOf(-90, 1, 45, 91, 360, 720).forEach { bad ->
            assertFailsWith<IllegalArgumentException>("rotationDegrees=$bad") { valid(rotationDegrees = bad) }
        }
    }

    @Test
    fun `all four legal rotations are accepted`() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            assertEquals(rotation, valid(rotationDegrees = rotation).rotationDegrees)
        }
    }

    @Test
    fun `negative timestamp is rejected`() {
        assertFailsWith<IllegalArgumentException> { valid(timestampNanos = -1L) }
        assertFailsWith<IllegalArgumentException> { valid(timestampNanos = Long.MIN_VALUE) }
    }

    @Test
    fun `zero timestamp is accepted`() {
        assertEquals(0L, valid(timestampNanos = 0L).timestampNanos)
    }

    @Test
    fun `timestamp is preserved exactly`() {
        val timestamps = listOf(0L, 1L, 123_456_789_012L, Long.MAX_VALUE)
        timestamps.forEach { ts -> assertEquals(ts, valid(timestampNanos = ts).timestampNanos) }
    }

    @Test
    fun `1920x1080 rotation 0 preserves dimensions unswapped`() {
        val metadata = valid(bufferWidthPx = 1920, bufferHeightPx = 1080, rotationDegrees = 0)
        assertEquals(1920, metadata.bufferWidthPx)
        assertEquals(1080, metadata.bufferHeightPx)
    }

    @Test
    fun `1080x1920 rotation 90 preserves buffer dimensions without auto-swap`() {
        // The buffer itself is portrait-shaped at 90 degrees rotation; the model must not
        // "correct" this to landscape - it records exactly what ImageProxy reports.
        val metadata = valid(bufferWidthPx = 1080, bufferHeightPx = 1920, rotationDegrees = 90)
        assertEquals(1080, metadata.bufferWidthPx)
        assertEquals(1920, metadata.bufferHeightPx)
        assertEquals(90, metadata.rotationDegrees)
    }

    @Test
    fun `landscape buffer dimensions are not swapped regardless of rotation value`() {
        listOf(0, 90, 180, 270).forEach { rotation ->
            val metadata = valid(bufferWidthPx = 1920, bufferHeightPx = 1080, rotationDegrees = rotation)
            assertEquals(1920, metadata.bufferWidthPx, "rotation=$rotation")
            assertEquals(1080, metadata.bufferHeightPx, "rotation=$rotation")
        }
    }

    @Test
    fun `crop rect may be entirely absent`() {
        val metadata = valid()
        assertEquals(null, metadata.cropRectLeftPx)
        assertEquals(null, metadata.cropRectTopPx)
        assertEquals(null, metadata.cropRectRightPx)
        assertEquals(null, metadata.cropRectBottomPx)
    }

    @Test
    fun `crop rect equal to the full buffer is accepted`() {
        val metadata =
            valid(
                bufferWidthPx = 1920,
                bufferHeightPx = 1080,
                cropRectLeftPx = 0,
                cropRectTopPx = 0,
                cropRectRightPx = 1920,
                cropRectBottomPx = 1080,
            )
        assertEquals(0, metadata.cropRectLeftPx)
        assertEquals(0, metadata.cropRectTopPx)
        assertEquals(1920, metadata.cropRectRightPx)
        assertEquals(1080, metadata.cropRectBottomPx)
    }

    @Test
    fun `crop rect strictly inside the buffer is accepted`() {
        val metadata =
            valid(
                bufferWidthPx = 1920,
                bufferHeightPx = 1080,
                cropRectLeftPx = 100,
                cropRectTopPx = 50,
                cropRectRightPx = 1800,
                cropRectBottomPx = 1000,
            )
        assertEquals(100, metadata.cropRectLeftPx)
        assertEquals(1800, metadata.cropRectRightPx)
    }

    @Test
    fun `partially present crop rect fields are rejected`() {
        assertFailsWith<IllegalArgumentException> { valid(cropRectLeftPx = 0) }
        assertFailsWith<IllegalArgumentException> { valid(cropRectLeftPx = 0, cropRectTopPx = 0) }
        assertFailsWith<IllegalArgumentException> { valid(cropRectLeftPx = 0, cropRectTopPx = 0, cropRectRightPx = 100) }
    }

    @Test
    fun `crop rect with negative left or top is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            valid(cropRectLeftPx = -1, cropRectTopPx = 0, cropRectRightPx = 100, cropRectBottomPx = 100)
        }
        assertFailsWith<IllegalArgumentException> {
            valid(cropRectLeftPx = 0, cropRectTopPx = -1, cropRectRightPx = 100, cropRectBottomPx = 100)
        }
    }

    @Test
    fun `crop rect that is not ordered (left greater or equal to right) is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            valid(cropRectLeftPx = 100, cropRectTopPx = 0, cropRectRightPx = 100, cropRectBottomPx = 100)
        }
        assertFailsWith<IllegalArgumentException> {
            valid(cropRectLeftPx = 200, cropRectTopPx = 0, cropRectRightPx = 100, cropRectBottomPx = 100)
        }
    }

    @Test
    fun `crop rect that is not ordered (top greater or equal to bottom) is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            valid(cropRectLeftPx = 0, cropRectTopPx = 100, cropRectRightPx = 200, cropRectBottomPx = 100)
        }
    }

    @Test
    fun `crop rect exceeding the buffer bounds is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            valid(
                bufferWidthPx = 1920,
                bufferHeightPx = 1080,
                cropRectLeftPx = 0,
                cropRectTopPx = 0,
                cropRectRightPx = 1921,
                cropRectBottomPx = 1080,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            valid(
                bufferWidthPx = 1920,
                bufferHeightPx = 1080,
                cropRectLeftPx = 0,
                cropRectTopPx = 0,
                cropRectRightPx = 1920,
                cropRectBottomPx = 1081,
            )
        }
    }

    // --- sensorToBufferTransform (CAM-2c) ---

    @Test
    fun `sensorToBufferTransform defaults to null`() {
        assertEquals(null, valid().sensorToBufferTransform)
    }

    @Test
    fun `sensorToBufferTransform is preserved exactly when present`() {
        val transform = SensorToBufferTransform(scaleX = 0.5, scaleY = 0.5, translateXPx = -100.0, translateYPx = -50.0)
        val metadata =
            CameraFrameMetadata(
                timestampNanos = 0L,
                bufferWidthPx = 1920,
                bufferHeightPx = 1080,
                rotationDegrees = 0,
                sensorToBufferTransform = transform,
            )
        assertEquals(transform, metadata.sensorToBufferTransform)
    }
}
