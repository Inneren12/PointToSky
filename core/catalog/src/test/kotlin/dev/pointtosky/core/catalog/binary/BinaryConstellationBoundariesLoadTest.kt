package dev.pointtosky.core.catalog.binary

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.catalog.ByteArrayAssetProvider
import dev.pointtosky.core.catalog.io.AssetProvider
import dev.pointtosky.core.logging.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

/**
 * Unit tests for constellation boundaries loader covering both success and failure scenarios.
 * Tests validate that:
 *   - Valid const_v1.bin → Real boundaries with correct status
 *   - Invalid const_v1.bin → Fake boundaries with clear failure reason
 */
class BinaryConstellationBoundariesLoadTest {

    @Test
    fun `valid const_v1_bin loads real boundaries with correct status`() {
        // Build minimal valid binary: header + 1 constellation record
        val payload = buildPayload(
            code = "ORI",
            minRa = 80.0,
            maxRa = 100.0,
            minDec = -10.0,
            maxDec = 10.0,
        )
        val crc = CRC32().apply { update(payload) }.value.toInt()

        val header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("PTSK".toByteArray(StandardCharsets.US_ASCII)) // magic
            put("CONS".toByteArray(StandardCharsets.US_ASCII)) // type
            putInt(1) // version
            putInt(1) // recordCount
            putInt(crc) // payloadCrc32
        }.array()

        val fileBytes = header + payload
        val provider = ByteArrayAssetProvider(
            mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to fileBytes),
        )
        val testLogger = TestLogger()

        val result = BinaryConstellationBoundaries.load(provider, logger = testLogger)

        // Verify status is Real, not Fake
        assertTrue("Expected Real status", result.status is ConstellationBoundariesStatus.Real)
        val realStatus = result.status as ConstellationBoundariesStatus.Real
        assertEquals("Expected 1 boundary record", 1, realStatus.metadata.recordCount)
        assertTrue("Expected positive file size", realStatus.metadata.sizeBytes > 0)

        // Verify boundaries work correctly
        val boundaries = result.boundaries
        val hit = boundaries.findByEq(Equatorial(90.0, 0.0))
        assertEquals("Expected ORI constellation", "ORI", hit)

        // Verify success was logged
        assertTrue("Expected success log", testLogger.infoMessages.any { it.contains("loaded successfully") })
        assertTrue("Expected no error logs", testLogger.errorMessages.isEmpty())
    }

    @Test
    fun `invalid header returns fake boundaries with clear reason`() {
        // Create file with invalid header (too short)
        val invalidBytes = byteArrayOf(1, 2, 3, 4, 5) // < 20 bytes
        val provider = ByteArrayAssetProvider(
            mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to invalidBytes),
        )
        val testLogger = TestLogger()

        val result = BinaryConstellationBoundaries.load(provider, logger = testLogger)

        // Verify status is Fake with reason
        assertTrue("Expected Fake status", result.status is ConstellationBoundariesStatus.Fake)
        val fakeStatus = result.status as ConstellationBoundariesStatus.Fake
        assertNotNull("Expected non-null reason", fakeStatus.reason)
        assertTrue("Expected 'Invalid header' in reason", fakeStatus.reason.contains("Invalid header"))

        // Verify fallback was logged exactly once
        assertEquals("Expected exactly 1 error log", 1, testLogger.errorMessages.size)
        val errorLog = testLogger.errorMessages[0]
        assertTrue("Expected fallback message", errorLog.contains("falling back to FakeConstellationBoundaries"))
        assertTrue("Expected reason in log", errorLog.contains("reason"))
    }

    @Test
    fun `wrong magic returns fake boundaries with clear reason`() {
        val payload = buildPayload("ORI", 80.0, 100.0, -10.0, 10.0)
        val crc = CRC32().apply { update(payload) }.value.toInt()

        // Wrong magic: "FAKE" instead of "PTSK"
        val header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("FAKE".toByteArray(StandardCharsets.US_ASCII)) // wrong magic
            put("CONS".toByteArray(StandardCharsets.US_ASCII))
            putInt(1)
            putInt(1)
            putInt(crc)
        }.array()

        val fileBytes = header + payload
        val provider = ByteArrayAssetProvider(
            mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to fileBytes),
        )
        val testLogger = TestLogger()

        val result = BinaryConstellationBoundaries.load(provider, logger = testLogger)

        assertTrue("Expected Fake status", result.status is ConstellationBoundariesStatus.Fake)
        val fakeStatus = result.status as ConstellationBoundariesStatus.Fake
        assertTrue("Expected 'Invalid header' in reason", fakeStatus.reason.contains("Invalid header"))
        assertEquals("Expected exactly 1 error log", 1, testLogger.errorMessages.size)
    }

    @Test
    fun `unsupported version returns fake boundaries with clear reason`() {
        val payload = buildPayload("ORI", 80.0, 100.0, -10.0, 10.0)
        val crc = CRC32().apply { update(payload) }.value.toInt()

        // Version 99 instead of 1
        val header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("PTSK".toByteArray(StandardCharsets.US_ASCII))
            put("CONS".toByteArray(StandardCharsets.US_ASCII))
            putInt(99) // unsupported version
            putInt(1)
            putInt(crc)
        }.array()

        val fileBytes = header + payload
        val provider = ByteArrayAssetProvider(
            mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to fileBytes),
        )
        val testLogger = TestLogger()

        val result = BinaryConstellationBoundaries.load(provider, logger = testLogger)

        assertTrue("Expected Fake status", result.status is ConstellationBoundariesStatus.Fake)
        val fakeStatus = result.status as ConstellationBoundariesStatus.Fake
        assertTrue("Expected 'Unsupported catalog' in reason", fakeStatus.reason.contains("Unsupported catalog"))
        assertTrue("Expected version in reason", fakeStatus.reason.contains("version=99"))
    }

    @Test
    fun `crc mismatch returns fake boundaries with clear reason`() {
        val payload = buildPayload("ORI", 80.0, 100.0, -10.0, 10.0)

        // Wrong CRC: use 0 instead of actual CRC
        val header = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("PTSK".toByteArray(StandardCharsets.US_ASCII))
            put("CONS".toByteArray(StandardCharsets.US_ASCII))
            putInt(1)
            putInt(1)
            putInt(0) // wrong CRC
        }.array()

        val fileBytes = header + payload
        val provider = ByteArrayAssetProvider(
            mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to fileBytes),
        )
        val testLogger = TestLogger()

        val result = BinaryConstellationBoundaries.load(provider, logger = testLogger)

        assertTrue("Expected Fake status", result.status is ConstellationBoundariesStatus.Fake)
        val fakeStatus = result.status as ConstellationBoundariesStatus.Fake
        assertTrue("Expected 'CRC mismatch' in reason", fakeStatus.reason.contains("CRC mismatch"))
        assertTrue("Expected expected/actual values", fakeStatus.reason.contains("expected=") && fakeStatus.reason.contains("actual="))
    }

    @Test
    fun `file not found returns fake boundaries with clear reason`() {
        // Provider with no files
        val provider = ByteArrayAssetProvider(emptyMap())
        val testLogger = TestLogger()

        val result = BinaryConstellationBoundaries.load(provider, logger = testLogger)

        assertTrue("Expected Fake status", result.status is ConstellationBoundariesStatus.Fake)
        val fakeStatus = result.status as ConstellationBoundariesStatus.Fake
        assertNotNull("Expected non-null reason", fakeStatus.reason)
        assertTrue("Expected 'Failed to open file' in reason", fakeStatus.reason.contains("Failed to open file"))
    }

    @Test
    fun `IOException during open returns fake boundaries`() {
        // Provider that throws IOException
        val provider = object : AssetProvider {
            override fun open(path: String): InputStream {
                throw IOException("Test IO error")
            }
            override fun exists(path: String): Boolean = false
            override fun list(path: String): List<String> = emptyList()
        }
        val testLogger = TestLogger()

        val result = BinaryConstellationBoundaries.load(provider, logger = testLogger)

        assertTrue("Expected Fake status", result.status is ConstellationBoundariesStatus.Fake)
        val fakeStatus = result.status as ConstellationBoundariesStatus.Fake
        assertTrue("Expected 'Failed to open file' in reason", fakeStatus.reason.contains("Failed to open file"))
    }

    private fun buildPayload(code: String, minRa: Double, maxRa: Double, minDec: Double, maxDec: Double): ByteArray {
        val nameBytes = code.toByteArray(StandardCharsets.UTF_8)
        val bb = ByteBuffer.allocate(4 + nameBytes.size + 8 * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        bb.putInt(nameBytes.size)
        bb.put(nameBytes)
        bb.putDouble(minRa)
        bb.putDouble(maxRa)
        bb.putDouble(minDec)
        bb.putDouble(maxDec)
        return bb.array()
    }

    /**
     * Test logger that captures log messages for verification.
     */
    private class TestLogger : Logger {
        val errorMessages = mutableListOf<String>()
        val infoMessages = mutableListOf<String>()

        override fun v(tag: String, msg: String, payload: Map<String, Any?>) {
            // No-op for tests
        }

        override fun d(tag: String, msg: String, payload: Map<String, Any?>) {
            // No-op for tests
        }

        override fun i(tag: String, msg: String, payload: Map<String, Any?>) {
            infoMessages.add("$tag: $msg $payload")
        }

        override fun w(tag: String, msg: String, err: Throwable?, payload: Map<String, Any?>) {
            // No-op for tests
        }

        override fun e(tag: String, msg: String, err: Throwable?, payload: Map<String, Any?>) {
            errorMessages.add("$tag: $msg ${err?.message ?: ""} $payload")
        }

        override fun event(name: String, payload: Map<String, Any?>) {
            // No-op for tests
        }
    }
}
