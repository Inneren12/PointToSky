package dev.pointtosky.core.astro.projection.camera.skylog

import dev.pointtosky.core.astro.projection.camera.prediction.PredictedStarClassification
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pure JVM tests for the SKY-1 JSONL codec. No file, no camera, no Android type — a log line is a
 * `String`, which is exactly what makes the format usable offline.
 */
class SkySessionLogCodecTest {
    private val fixtures = SkySessionLogFixtures

    // -----------------------------------------------------------------------------------------
    // Round trip (tests §1)
    // -----------------------------------------------------------------------------------------

    @Test
    fun `header round-trips field for field`() {
        val header = fixtures.header(calibration = fixtures.calibration())

        val parsed = assertIs<SkySessionLogLine.Header>(parseSkySessionLogLine(encodeSkySessionHeaderLine(header), 1)).header

        // The pinhole block is derived-on-write and deliberately not parsed back, so it is compared
        // separately from everything else rather than silently weakening the field-for-field claim.
        assertEquals(header.copy(intrinsics = header.intrinsics.copy(pinhole = null)), parsed)
        assertNull(parsed.intrinsics.pinhole, "pinhole is derived on write and must not be parsed back")
    }

    @Test
    fun `frame round-trips field for field`() {
        val record =
            fixtures.frameRecord(
                sequence = 42L,
                frame = fixtures.frameMetadata(rotationDegrees = 90, withCropRect = true, withSensorToBufferTransform = true),
                predictedStars =
                    listOf(
                        fixtures.predictedStar(catalogIndex = 101),
                        fixtures.predictedStar(
                            catalogIndex = 202,
                            classification = PredictedStarClassification.BEHIND_CAMERA,
                            imageXPx = null,
                            imageYPx = null,
                            displayXPx = null,
                            displayYPx = null,
                        ),
                    ),
            )

        val parsed = assertIs<SkySessionLogLine.Frame>(parseSkySessionLogLine(encodeSkyFrameLine(record), 1)).record

        assertEquals(record, parsed)
    }

    @Test
    fun `frame without observer, exposure or stars round-trips`() {
        val record = fixtures.frameRecord(observer = null, exposure = null, predictedStars = emptyList())

        val parsed = assertIs<SkySessionLogLine.Frame>(parseSkySessionLogLine(encodeSkyFrameLine(record), 1)).record

        assertEquals(record, parsed)
    }

    @Test
    fun `encoded lines never contain a newline`() {
        val header = encodeSkySessionHeaderLine(fixtures.header(calibration = fixtures.calibration()))
        val frame = encodeSkyFrameLine(fixtures.frameRecord(predictedStars = listOf(fixtures.predictedStar())))

        assertTrue(header.none { it == '\n' || it == '\r' }, "header line must stay on one line")
        assertTrue(frame.none { it == '\n' || it == '\r' }, "frame line must stay on one line")
    }

    // -----------------------------------------------------------------------------------------
    // Predicted stars (tests §3)
    // -----------------------------------------------------------------------------------------

    @Test
    fun `predicted star id, magnitude and pixel coordinates survive the round trip`() {
        val star =
            SkyPredictedStar(
                catalogIndex = 5_318_008,
                rightAscensionRad = 2.7182818,
                declinationRad = -0.5772157,
                magnitude = -1.46,
                classification = PredictedStarClassification.INSIDE_IMAGE_OUTSIDE_VIEWPORT,
                imageXPx = 123.456789,
                imageYPx = 987.654321,
                displayXPx = 1000.5,
                displayYPx = 2000.25,
            )
        val record = fixtures.frameRecord(predictedStars = listOf(star))

        val parsed = assertIs<SkySessionLogLine.Frame>(parseSkySessionLogLine(encodeSkyFrameLine(record), 1)).record
        val parsedStar = parsed.predictedStars.single()

        assertEquals(star.catalogIndex, parsedStar.catalogIndex)
        assertEquals(star.magnitude, parsedStar.magnitude)
        assertEquals(star.imageXPx, parsedStar.imageXPx)
        assertEquals(star.imageYPx, parsedStar.imageYPx)
        assertEquals(star.displayXPx, parsedStar.displayXPx)
        assertEquals(star.displayYPx, parsedStar.displayYPx)
        assertEquals(star.classification, parsedStar.classification)
        assertEquals(star.rightAscensionRad, parsedStar.rightAscensionRad)
        assertEquals(star.declinationRad, parsedStar.declinationRad)
    }

    @Test
    fun `a star without a magnitude keeps a null magnitude rather than becoming zero`() {
        val record = fixtures.frameRecord(predictedStars = listOf(fixtures.predictedStar().copy(magnitude = null)))

        val parsed = assertIs<SkySessionLogLine.Frame>(parseSkySessionLogLine(encodeSkyFrameLine(record), 1)).record

        assertNull(parsed.predictedStars.single().magnitude)
    }

    @Test
    fun `predicted star order is preserved`() {
        val stars = (0 until 12).map { fixtures.predictedStar(catalogIndex = 100 + it) }
        val record = fixtures.frameRecord(predictedStars = stars)

        val parsed = assertIs<SkySessionLogLine.Frame>(parseSkySessionLogLine(encodeSkyFrameLine(record), 1)).record

        assertEquals(stars.map { it.catalogIndex }, parsed.predictedStars.map { it.catalogIndex })
    }

    // -----------------------------------------------------------------------------------------
    // Luma reference consistency (tests §5)
    // -----------------------------------------------------------------------------------------

    @Test
    fun `luma reference round-trips width, height and stride`() {
        val record = fixtures.frameRecord(luma = fixtures.lumaReference(rowStridePx = SkySessionLogFixtures.BUFFER_WIDTH_PX + 32))

        val parsed = assertIs<SkySessionLogLine.Frame>(parseSkySessionLogLine(encodeSkyFrameLine(record), 1)).record

        assertEquals(record.luma, parsed.luma)
        assertEquals(SkySessionLogFixtures.BUFFER_WIDTH_PX + 32, parsed.luma.rowStridePx)
    }

    @Test
    fun `a luma reference whose byteLength cannot hold its own geometry is rejected`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                SkyLumaReference(
                    path = "frames/frame_000000.y",
                    format = SkyLumaFormat.RAW_Y8,
                    widthPx = 640,
                    heightPx = 480,
                    rowStridePx = 640,
                    byteLength = 640L * 480L - 1L,
                )
            }
        assertTrue(exception.message.orEmpty().contains("byteLength"))
    }

    @Test
    fun `a luma reference whose stride is narrower than its width is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            SkyLumaReference(
                path = "frames/frame_000000.y",
                format = SkyLumaFormat.RAW_Y8,
                widthPx = 640,
                heightPx = 480,
                rowStridePx = 639,
                byteLength = 640L * 480L,
            )
        }
    }

    @Test
    fun `a luma reference that disagrees with its own frame buffer is rejected`() {
        val exception =
            assertFailsWith<IllegalArgumentException> {
                fixtures.frameRecord(
                    luma =
                        SkyLumaReference(
                            path = "frames/frame_000000.y",
                            format = SkyLumaFormat.RAW_Y8,
                            widthPx = SkySessionLogFixtures.BUFFER_WIDTH_PX / 2,
                            heightPx = SkySessionLogFixtures.BUFFER_HEIGHT_PX,
                            rowStridePx = SkySessionLogFixtures.BUFFER_WIDTH_PX / 2,
                            byteLength = 1L * (SkySessionLogFixtures.BUFFER_WIDTH_PX / 2) * SkySessionLogFixtures.BUFFER_HEIGHT_PX,
                        ),
                )
            }
        assertTrue(exception.message.orEmpty().contains("must match the frame buffer"))
    }

    @Test
    fun `an absolute luma path is rejected so a session directory stays relocatable`() {
        assertFailsWith<IllegalArgumentException> {
            SkyLumaReference(
                path = "/data/user/0/dev.pointtosky/files/sky/frame_000000.y",
                format = SkyLumaFormat.RAW_Y8,
                widthPx = 8,
                heightPx = 8,
                rowStridePx = 8,
                byteLength = 64L,
            )
        }
    }

    // -----------------------------------------------------------------------------------------
    // Whole-document parsing and damage tolerance
    // -----------------------------------------------------------------------------------------

    @Test
    fun `a whole log parses into a header and its frames`() {
        val header = fixtures.header()
        val records = (0L until 5L).map { fixtures.frameRecord(sequence = it) }
        val text =
            buildString {
                appendLine(encodeSkySessionHeaderLine(header))
                records.forEach { appendLine(encodeSkyFrameLine(it)) }
            }

        val document = parseSkySessionLog(text)

        assertEquals(header.copy(intrinsics = header.intrinsics.copy(pinhole = null)), document.header)
        assertEquals(records, document.records)
        assertEquals(emptyList(), document.unreadable)
    }

    @Test
    fun `a truncated final line costs one frame, not the session`() {
        val header = fixtures.header()
        val records = (0L until 3L).map { fixtures.frameRecord(sequence = it) }
        val fullText =
            buildString {
                appendLine(encodeSkySessionHeaderLine(header))
                records.forEach { appendLine(encodeSkyFrameLine(it)) }
            }
        val truncated = fullText + encodeSkyFrameLine(fixtures.frameRecord(sequence = 3L)).take(40)

        val document = parseSkySessionLog(truncated)

        assertNotNull(document.header)
        assertEquals(records, document.records)
        assertEquals(1, document.unreadable.size)
        assertEquals(5, document.unreadable.single().lineNumber)
    }

    @Test
    fun `a second header line is reported rather than replacing the first`() {
        val first = fixtures.header()
        val second = fixtures.header(intrinsics = fixtures.intrinsics(widthPx = 1280, heightPx = 720))
        val text =
            buildString {
                appendLine(encodeSkySessionHeaderLine(first))
                appendLine(encodeSkyFrameLine(fixtures.frameRecord()))
                appendLine(encodeSkySessionHeaderLine(second))
            }

        val document = parseSkySessionLog(text)

        assertEquals(first.sessionId, document.header?.sessionId)
        assertEquals(
            SkySessionLogFixtures.BUFFER_WIDTH_PX,
            document.header?.intrinsics?.referenceWidthPx,
            "the first header must win, not the last",
        )
        assertEquals(1, document.unreadable.size)
        assertTrue(document.unreadable.single().reason.contains("duplicate"))
    }

    @Test
    fun `an unknown kind is reported without aborting the read`() {
        val line = parseSkySessionLogLine("""{"kind":"detection","seq":0}""", 7)

        val unreadable = assertIs<SkySessionLogLine.Unreadable>(line)
        assertEquals(7, unreadable.lineNumber)
        assertTrue(unreadable.reason.contains("detection"))
    }

    @Test
    fun `a frame line missing a required field is reported without throwing`() {
        val line = parseSkySessionLogLine("""{"kind":"frame","seq":0}""", 3)

        val unreadable = assertIs<SkySessionLogLine.Unreadable>(line)
        assertTrue(unreadable.reason.isNotBlank())
    }

    @Test
    fun `unknown forward-compatible fields are ignored`() {
        val record = fixtures.frameRecord()
        val withExtra = encodeSkyFrameLine(record).dropLast(1) + ""","detections":[{"xPx":1.0}]}"""

        val parsed = assertIs<SkySessionLogLine.Frame>(parseSkySessionLogLine(withExtra, 1)).record

        assertEquals(record, parsed)
    }

    @Test
    fun `an empty log parses to an empty document`() {
        val document = parseSkySessionLog("")

        assertNull(document.header)
        assertEquals(emptyList(), document.records)
        assertEquals(emptyList(), document.unreadable)
    }
}
