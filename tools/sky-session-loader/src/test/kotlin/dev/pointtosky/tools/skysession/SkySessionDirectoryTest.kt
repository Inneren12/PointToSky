package dev.pointtosky.tools.skysession

import dev.pointtosky.core.astro.projection.camera.detect.LumaFrame
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaFormat
import dev.pointtosky.core.astro.projection.camera.skylog.SkyLumaReference
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The file seam itself: what is written is what is read back, and every failure is a value. */
class SkySessionDirectoryTest {
    @TempDir
    lateinit var sessionDirectory: File

    @Test
    fun `a written frame reads back byte for byte, stride and all`() {
        val frame = SyntheticSession.selfConsistentFrame(sequence = 0L)
        SyntheticSession.write(sessionDirectory, SyntheticSession.header(), listOf(frame))
        val reference = frame.record.luma

        val onDisk = File(sessionDirectory, reference.path)
        assertEquals(reference.byteLength, onDisk.length())
        assertContentEquals(frame.data, onDisk.readBytes(), "the .y file is the plane verbatim, no header")

        val loaded = assertIs<SkyLumaReadResult.Loaded>(readSkyLumaFrame(sessionDirectory, reference)).frame
        assertEquals(reference.widthPx, loaded.widthPx)
        assertEquals(reference.heightPx, loaded.heightPx)
        assertEquals(reference.rowStridePx, loaded.rowStridePx)
        assertTrue(reference.rowStridePx > reference.widthPx, "the fixture must exercise padded rows")

        // Every addressable pixel, against the in-memory frame the metrics were computed from. A reader
        // that packed rows at widthPx would smear the padding across the image and fail here.
        val inMemory = LumaFrame.forReference(reference, frame.data)
        for (y in 0 until reference.heightPx) {
            for (x in 0 until reference.widthPx) {
                assertEquals(inMemory.lumaAt(x, y), loaded.lumaAt(x, y), "pixel ($x, $y)")
            }
        }
    }

    @Test
    fun `a truncated frame file is refused, not detected in`() {
        val frame = SyntheticSession.selfConsistentFrame(sequence = 0L)
        SyntheticSession.write(sessionDirectory, SyntheticSession.header(), listOf(frame))
        val onDisk = File(sessionDirectory, frame.record.luma.path)
        onDisk.writeBytes(frame.data.copyOf(frame.data.size - 1))

        val failed = assertIs<SkyLumaReadResult.Failed>(readSkyLumaFrame(sessionDirectory, frame.record.luma))
        assertEquals(SkyLumaReadFailure.LENGTH_MISMATCH, failed.reason)
    }

    @Test
    fun `a path that climbs out of the session directory is never followed`() {
        val outside = File(sessionDirectory.parentFile, "outside.y")
        val reference =
            SkyLumaReference(
                path = "../${outside.name}",
                format = SkyLumaFormat.RAW_Y8,
                widthPx = 4,
                heightPx = 2,
                rowStridePx = 4,
                byteLength = 8L,
            )
        outside.writeBytes(ByteArray(8))
        try {
            val failed = assertIs<SkyLumaReadResult.Failed>(readSkyLumaFrame(sessionDirectory, reference))
            assertEquals(SkyLumaReadFailure.PATH_ESCAPES_SESSION, failed.reason)
        } finally {
            outside.delete()
        }
    }

    @Test
    fun `a session directory that is a file reports NOT_A_DIRECTORY`() {
        val file = File(sessionDirectory, "not-a-session")
        file.writeText("")

        val failed = assertIs<SkySessionLoadResult.Failed>(loadSkySessionLog(file))
        assertEquals(SkySessionLoadFailure.NOT_A_DIRECTORY, failed.reason)
    }

    @Test
    fun `the log file name and frame layout match what the writer produces`() {
        val frame = SyntheticSession.selfConsistentFrame(sequence = 42L, renderStars = false, noiseSigma = 0.0)
        SyntheticSession.write(sessionDirectory, SyntheticSession.header(), listOf(frame))

        assertTrue(File(sessionDirectory, "session.jsonl").isFile)
        assertTrue(File(sessionDirectory, "frames/frame_000042.y").isFile)
        val loaded = assertIs<SkySessionLoadResult.Loaded>(loadSkySessionLog(sessionDirectory))
        assertEquals(1, loaded.document.records.size)
        val record = loaded.document.records[0]
        assertEquals("frames/frame_000042.y", record.luma.path)
        assertTrue(loaded.document.unreadable.isEmpty())
        assertTrue(loaded.document.orphanFrames.isEmpty())
    }
}
