package dev.pointtosky.mobile.ar.camera

import dev.pointtosky.core.astro.projection.camera.skylog.SkySessionLogHeader
import dev.pointtosky.core.astro.projection.camera.skylog.parseSkySessionLog
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKY-1 (`internalDebug`-only): a session creates its directory or refuses to run.
 *
 * `session.jsonl` opened for append on a directory that already had one produces a file with two
 * header lines, two interleaved frame sequences, and a `frame_000000.y` belonging to whichever
 * session wrote it last — a dataset that parses cleanly and is entirely wrong. These tests pin that
 * none of that is reachable.
 */
class SkySessionLogWriterCollisionTest {
    private val fixtures = SkySessionCaptureFixtures
    private val temporaryRoots = mutableListOf<File>()

    @AfterTest
    fun cleanUp() {
        temporaryRoots.forEach { it.deleteRecursively() }
    }

    private fun newRoot(): File = Files.createTempDirectory("sky_writer_test").toFile().also { temporaryRoots += it }

    private fun header(sessionId: String = "sky_test"): SkySessionLogHeader =
        buildSkySessionHeader(
            sessionId = sessionId,
            startedAtEpochMillis = 1_767_225_600_000L,
            bufferWidthPx = SkySessionCaptureFixtures.BUFFER_WIDTH_PX,
            bufferHeightPx = SkySessionCaptureFixtures.BUFFER_HEIGHT_PX,
            intrinsics = fixtures.intrinsics(),
            clockAlignment = skyClockAlignmentFor(SkyCameraTimestampSource.REALTIME),
            maxPairDeltaNanos = 25_000_000L,
            clockMismatchThresholdNanos = 5_000_000_000L,
            deviceModel = "Test Device",
            cameraId = "3",
            physicalCameraIds = listOf("2", "3"),
            calibration = null,
            pinhole = null,
            notes = null,
        )

    @Test
    fun `a fresh directory is created and the header written`() {
        val root = newRoot()
        val writer = SkySessionLogWriter(File(root, "session"))

        assertTrue(writer.start(header()))

        assertNull(writer.lastFailure)
        assertTrue(File(root, "session/$SKY_SESSION_LOG_FILE_NAME").isFile)
        assertTrue(File(root, "session/$SKY_SESSION_FRAMES_DIRECTORY_NAME").isDirectory)
    }

    @Test
    fun `an existing session directory is refused, never adopted`() {
        val root = newRoot()
        val directory = File(root, "session")
        assertTrue(directory.mkdirs())

        val writer = SkySessionLogWriter(directory)

        assertFalse(writer.start(header()))
        assertEquals(SkySessionWriteFailure.SESSION_DIRECTORY_EXISTS, writer.lastFailure)
    }

    @Test
    fun `a second writer cannot append to an existing session`() {
        val root = newRoot()
        val directory = File(root, "session")
        val first = SkySessionLogWriter(directory)
        assertTrue(first.start(header("first")))
        first.close()

        val second = SkySessionLogWriter(directory)

        assertFalse(second.start(header("second")))
        assertEquals(SkySessionWriteFailure.SESSION_DIRECTORY_EXISTS, second.lastFailure)

        val document = parseSkySessionLog(File(directory, SKY_SESSION_LOG_FILE_NAME).readText())
        assertEquals("first", assertNotNull(document.header).sessionId)
        assertEquals(emptyList(), document.unreadable, "the log must still contain exactly one header line")
    }

    @Test
    fun `a pre-existing log file inside a freshly named directory is refused`() {
        // The directory does not exist, but its intended path is occupied by a plain file, so creating
        // it must fail rather than silently writing somewhere else.
        val root = newRoot()
        val directory = File(root, "session")
        assertTrue(directory.createNewFile())

        val writer = SkySessionLogWriter(directory)

        assertFalse(writer.start(header()))
        assertEquals(SkySessionWriteFailure.SESSION_DIRECTORY_EXISTS, writer.lastFailure)
    }

    @Test
    fun `an existing luma file is never overwritten`() {
        val root = newRoot()
        val directory = File(root, "session")
        val writer = SkySessionLogWriter(directory)
        assertTrue(writer.start(header()))

        val occupied = File(directory, "$SKY_SESSION_FRAMES_DIRECTORY_NAME/${SkySessionLogWriter.lumaFileName(0L)}")
        occupied.writeBytes(ByteArray(3) { 0x7F })

        val reference =
            writer.writeLumaFrame(
                sequence = 0L,
                data = fixtures.lumaData(),
                widthPx = SkySessionCaptureFixtures.BUFFER_WIDTH_PX,
                heightPx = SkySessionCaptureFixtures.BUFFER_HEIGHT_PX,
                rowStridePx = SkySessionCaptureFixtures.BUFFER_WIDTH_PX,
            )

        assertNull(reference)
        assertEquals(SkySessionWriteFailure.LUMA_FILE_EXISTS, writer.lastFailure)
        assertEquals(3L, occupied.length(), "the existing file must be untouched")
        assertEquals(0L, writer.writtenLumaBytes)
    }

    @Test
    fun `writing before start is refused with a typed reason`() {
        val writer = SkySessionLogWriter(File(newRoot(), "session"))

        val reference =
            writer.writeLumaFrame(
                sequence = 0L,
                data = fixtures.lumaData(),
                widthPx = SkySessionCaptureFixtures.BUFFER_WIDTH_PX,
                heightPx = SkySessionCaptureFixtures.BUFFER_HEIGHT_PX,
                rowStridePx = SkySessionCaptureFixtures.BUFFER_WIDTH_PX,
            )

        assertNull(reference)
        assertEquals(SkySessionWriteFailure.NOT_STARTED, writer.lastFailure)
    }

    @Test
    fun `a buffer too small for its claimed geometry is refused`() {
        val root = newRoot()
        val writer = SkySessionLogWriter(File(root, "session"))
        assertTrue(writer.start(header()))

        val reference =
            writer.writeLumaFrame(
                sequence = 0L,
                data = ByteArray(10),
                widthPx = SkySessionCaptureFixtures.BUFFER_WIDTH_PX,
                heightPx = SkySessionCaptureFixtures.BUFFER_HEIGHT_PX,
                rowStridePx = SkySessionCaptureFixtures.BUFFER_WIDTH_PX,
            )

        assertNull(reference)
        assertEquals(SkySessionWriteFailure.LUMA_BUFFER_SHORT, writer.lastFailure)
        assertFalse(
            File(root, "session/$SKY_SESSION_FRAMES_DIRECTORY_NAME/${SkySessionLogWriter.lumaFileName(0L)}").exists(),
        )
    }

    @Test
    fun `a stride narrower than the width is refused`() {
        val root = newRoot()
        val writer = SkySessionLogWriter(File(root, "session"))
        assertTrue(writer.start(header()))

        val reference =
            writer.writeLumaFrame(
                sequence = 0L,
                data = fixtures.lumaData(),
                widthPx = SkySessionCaptureFixtures.BUFFER_WIDTH_PX,
                heightPx = SkySessionCaptureFixtures.BUFFER_HEIGHT_PX,
                rowStridePx = SkySessionCaptureFixtures.BUFFER_WIDTH_PX - 1,
            )

        assertNull(reference)
        assertEquals(SkySessionWriteFailure.LUMA_BUFFER_SHORT, writer.lastFailure)
    }

    @Test
    fun `two writers under one root get independent session directories`() {
        val root = newRoot()
        val first = SkySessionLogWriter(File(root, "sky_1"))
        val second = SkySessionLogWriter(File(root, "sky_2"))

        assertTrue(first.start(header("one")))
        assertTrue(second.start(header("two")))

        assertEquals(
            "one",
            assertNotNull(
                parseSkySessionLog(File(root, "sky_1/$SKY_SESSION_LOG_FILE_NAME").readText()).header,
            ).sessionId,
        )
        assertEquals(
            "two",
            assertNotNull(
                parseSkySessionLog(File(root, "sky_2/$SKY_SESSION_LOG_FILE_NAME").readText()).header,
            ).sessionId,
        )
    }

    @Test
    fun `close is idempotent`() {
        val writer = SkySessionLogWriter(File(newRoot(), "session"))
        assertTrue(writer.start(header()))

        writer.close()
        writer.close()

        assertNull(writer.lastFailure)
    }
}
