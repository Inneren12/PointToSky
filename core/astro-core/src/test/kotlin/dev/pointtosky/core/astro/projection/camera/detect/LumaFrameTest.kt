package dev.pointtosky.core.astro.projection.camera.detect

import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaFormat
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LumaFrameTest {
    @Test
    fun `reads unsigned intensities`() {
        // 200 stored as a signed byte is -56; a frame that returned that would invert the sky.
        val frame = LumaFrame(data = byteArrayOf(0, 200.toByte(), 255.toByte(), 1), widthPx = 2, heightPx = 2)

        assertEquals(0, frame.lumaAt(0, 0))
        assertEquals(200, frame.lumaAt(1, 0))
        assertEquals(255, frame.lumaAt(0, 1))
        assertEquals(1, frame.lumaAt(1, 1))
    }

    @Test
    fun `skips row padding`() {
        // 2x2 image inside a 4-byte stride; the padding bytes must never be addressable as pixels.
        val data = byteArrayOf(10, 11, 99, 99, 12, 13, 99, 99)
        val frame = LumaFrame(data = data, widthPx = 2, heightPx = 2, rowStridePx = 4)

        assertEquals(
            listOf(10, 11, 12, 13),
            listOf(frame.lumaAt(0, 0), frame.lumaAt(1, 0), frame.lumaAt(0, 1), frame.lumaAt(1, 1)),
        )
        assertEquals(4, frame.pixelCount)
    }

    @Test
    fun `accepts a buffer whose last row has no padding`() {
        // The minimum bound is full rows for all but the last, then one row of pixels — the same bound
        // SkyLumaReference enforces, so a file it accepts must load here.
        val data = ByteArray(4 * 1 + 2)
        val frame = LumaFrame(data = data, widthPx = 2, heightPx = 2, rowStridePx = 4)

        assertEquals(6L, frame.minimumByteLength)
        assertEquals(0, frame.lumaAt(1, 1))
    }

    @Test
    fun `rejects a buffer too small for its geometry`() {
        assertFailsWith<IllegalArgumentException> {
            LumaFrame(data = ByteArray(5), widthPx = 2, heightPx = 2, rowStridePx = 4)
        }
    }

    @Test
    fun `rejects a stride narrower than the image`() {
        assertFailsWith<IllegalArgumentException> {
            LumaFrame(data = ByteArray(64), widthPx = 8, heightPx = 4, rowStridePx = 4)
        }
    }

    @Test
    fun `builds a frame from a SKY-1 luma reference`() {
        val reference = reference(rowStridePx = 40)
        val data = ByteArray(reference.byteLength.toInt())
        data[3 * 40 + 7] = 123

        val frame = LumaFrame.forReference(reference, data)

        assertEquals(32, frame.widthPx)
        assertEquals(40, frame.rowStridePx)
        assertEquals(123, frame.lumaAt(7, 3))
    }

    @Test
    fun `refuses a truncated frame file`() {
        val reference = reference(rowStridePx = 40)

        // A capture killed mid-flush leaves a short file. Detecting stars in whatever the tail happens
        // to hold would be worse than failing.
        assertFailsWith<IllegalArgumentException> {
            LumaFrame.forReference(reference, ByteArray(reference.byteLength.toInt() - 40))
        }
    }

    private fun reference(rowStridePx: Int): SkyLumaReference =
        SkyLumaReference(
            path = "frames/frame_000000.y",
            format = SkyLumaFormat.RAW_Y8,
            widthPx = 32,
            heightPx = 24,
            rowStridePx = rowStridePx,
            byteLength = rowStridePx.toLong() * 24,
        )
}
