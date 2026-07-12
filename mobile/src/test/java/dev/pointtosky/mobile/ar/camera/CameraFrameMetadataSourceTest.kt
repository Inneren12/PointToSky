package dev.pointtosky.mobile.ar.camera

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
}
