package dev.pointtosky.core.astro.projection.camera.skylog

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SKY-1 codec: the `schemaVersion` gate and header ordering.
 *
 * A version number that can be silently defaulted is worse than no version number: it turns "this
 * build must not read this file" into "this build read this file and produced numbers". These tests
 * pin that a header without a supported, explicitly-declared version is never decoded, and that
 * frames written before a valid header are never attributed to a later one.
 */
class SkySessionLogSchemaVersionTest {
    private val fixtures = SkySessionLogFixtures

    private fun headerLineWith(schemaVersionJson: String?): String {
        val encoded = encodeSkySessionHeaderLine(fixtures.header())
        val withoutVersion = encoded.replace(Regex(""""schemaVersion":-?\d+,"""), "")
        return if (schemaVersionJson == null) {
            withoutVersion
        } else {
            withoutVersion.replaceFirst(
                """{"kind":"session",""",
                """{"kind":"session","schemaVersion":$schemaVersionJson,""",
            )
        }
    }

    @Test
    fun `the current schema version is in the supported set`() {
        assertTrue(SKY_SESSION_LOG_SCHEMA_VERSION in SUPPORTED_SKY_SESSION_LOG_SCHEMA_VERSIONS)
    }

    @Test
    fun `a header at the current version decodes`() {
        val line = parseSkySessionLogLine(headerLineWith("$SKY_SESSION_LOG_SCHEMA_VERSION"), 1)

        assertEquals(SKY_SESSION_LOG_SCHEMA_VERSION, assertIs<SkySessionLogLine.Header>(line).header.schemaVersion)
    }

    @Test
    fun `a header with no schemaVersion is unsupported, never defaulted to the current version`() {
        val line = parseSkySessionLogLine(headerLineWith(null), 4)

        val unsupported = assertIs<SkySessionLogLine.UnsupportedSchema>(line)
        assertEquals(4, unsupported.lineNumber)
        assertNull(unsupported.schemaVersion)
    }

    @Test
    fun `a non-integer schemaVersion is unsupported`() {
        val line = parseSkySessionLogLine(headerLineWith(""""one""""), 1)

        assertNull(assertIs<SkySessionLogLine.UnsupportedSchema>(line).schemaVersion)
    }

    @Test
    fun `a fractional schemaVersion is unsupported`() {
        val line = parseSkySessionLogLine(headerLineWith("1.5"), 1)

        assertNull(assertIs<SkySessionLogLine.UnsupportedSchema>(line).schemaVersion)
    }

    @Test
    fun `a zero schemaVersion is unsupported`() {
        assertEquals(
            0,
            assertIs<SkySessionLogLine.UnsupportedSchema>(parseSkySessionLogLine(headerLineWith("0"), 1)).schemaVersion,
        )
    }

    @Test
    fun `a negative schemaVersion is unsupported`() {
        assertEquals(
            -1,
            assertIs<SkySessionLogLine.UnsupportedSchema>(
                parseSkySessionLogLine(headerLineWith("-1"), 1),
            ).schemaVersion,
        )
    }

    @Test
    fun `a future schemaVersion is unsupported rather than parsed hopefully`() {
        val future = SUPPORTED_SKY_SESSION_LOG_SCHEMA_VERSIONS.max() + 1

        val line = parseSkySessionLogLine(headerLineWith("$future"), 1)

        assertEquals(future, assertIs<SkySessionLogLine.UnsupportedSchema>(line).schemaVersion)
    }

    @Test
    fun `schema v1 is refused rather than reinterpreted under v2's clock rules`() {
        // v1 could only say "an offset is recorded" or "none is". The writer emitted 0 on every
        // session without measuring anything, and nothing in a v1 log distinguishes that from a real
        // measurement - so it is refused, not read under rules it was never written against.
        assertEquals(
            1,
            assertIs<SkySessionLogLine.UnsupportedSchema>(parseSkySessionLogLine(headerLineWith("1"), 1)).schemaVersion,
        )
    }

    @Test
    fun `the current schema version is what a freshly encoded header carries`() {
        val parsed =
            assertIs<SkySessionLogLine.Header>(
                parseSkySessionLogLine(encodeSkySessionHeaderLine(fixtures.header()), 1),
            ).header

        assertEquals(SKY_SESSION_LOG_SCHEMA_VERSION, parsed.schemaVersion)
        assertEquals(2, SKY_SESSION_LOG_SCHEMA_VERSION, "the clock-provenance field bumped this to 2")
    }

    @Test
    fun `a document whose only header is unsupported has no header and cannot be replayed`() {
        val text =
            buildString {
                appendLine(headerLineWith("99"))
                appendLine(encodeSkyFrameLine(fixtures.frameRecord()))
            }

        val document = parseSkySessionLog(text)

        assertNull(document.header)
        assertEquals(1, document.unsupportedSchema.size)
        assertEquals(99, document.unsupportedSchema.single().schemaVersion)
        assertNull(replaySkySessionLog(document), "a document with no accepted header must not replay")
    }

    @Test
    fun `an unsupported header does not consume the header slot for a later supported one`() {
        // Two concatenated sessions, the first written by an unreadable future build. The supported
        // header must still be accepted, and only the frames after it belong to it.
        val text =
            buildString {
                appendLine(headerLineWith("99"))
                appendLine(encodeSkyFrameLine(fixtures.frameRecord(sequence = 0L)))
                appendLine(encodeSkySessionHeaderLine(fixtures.header()))
                appendLine(encodeSkyFrameLine(fixtures.frameRecord(sequence = 1L)))
            }

        val document = parseSkySessionLog(text)

        assertNotNull(document.header)
        assertEquals(listOf(1L), document.records.map { it.sequence })
        assertEquals(listOf(0L), document.orphanFrames.map { it.sequence })
    }

    // -----------------------------------------------------------------------------------------
    // Header ordering
    // -----------------------------------------------------------------------------------------

    @Test
    fun `frames before the header are orphaned, never replayed against it`() {
        val text =
            buildString {
                appendLine(encodeSkyFrameLine(fixtures.frameRecord(sequence = 7L)))
                appendLine(encodeSkySessionHeaderLine(fixtures.header()))
                appendLine(encodeSkyFrameLine(fixtures.frameRecord(sequence = 8L)))
            }

        val document = parseSkySessionLog(text)
        val report = assertNotNull(replaySkySessionLog(document))

        assertEquals(listOf(7L), document.orphanFrames.map { it.sequence })
        assertEquals(listOf(8L), document.records.map { it.sequence })
        assertEquals(listOf(8L), report.frames.map { it.sequence }, "replay must never touch orphan frames")
    }

    @Test
    fun `a log that is only frames orphans all of them and has no header`() {
        val text =
            buildString {
                (0L until 3L).forEach { appendLine(encodeSkyFrameLine(fixtures.frameRecord(sequence = it))) }
            }

        val document = parseSkySessionLog(text)

        assertNull(document.header)
        assertEquals(emptyList(), document.records)
        assertEquals(listOf(0L, 1L, 2L), document.orphanFrames.map { it.sequence })
    }

    @Test
    fun `truncated-final-line tolerance survives the schema gate`() {
        val text =
            encodeSkySessionHeaderLine(fixtures.header()) + "\n" +
                encodeSkyFrameLine(fixtures.frameRecord(sequence = 0L)) + "\n" +
                encodeSkyFrameLine(fixtures.frameRecord(sequence = 1L)).take(30)

        val document = parseSkySessionLog(text)

        assertNotNull(document.header)
        assertEquals(listOf(0L), document.records.map { it.sequence })
        assertEquals(1, document.unreadable.size)
        assertEquals(emptyList(), document.orphanFrames)
    }
}
