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
import java.util.zip.CRC32

/**
 * Unit tests for constellation boundaries loader covering both success and failure scenarios.
 * Tests validate that:
 *   - Valid PTSKCONS polygon const_v1.bin → Real boundaries with correct status
 *   - Invalid const_v1.bin → Fake boundaries with clear failure reason
 *   - Point-in-polygon lookup returns the correct IAU code
 */
class BinaryConstellationBoundariesLoadTest {

    private val builder = TestBinaryBuilder()

    @Test
    fun `valid const_v1_bin loads real boundaries with correct status`() {
        val binary = builder.build(mapOf(
            "ORI" to listOf(listOf(80.0 to -10.0, 80.0 to 10.0, 100.0 to 10.0, 100.0 to -10.0)),
        ))
        val provider = ByteArrayAssetProvider(mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to binary))
        val testLogger = TestLogger()

        val result = BinaryConstellationBoundaries.load(provider, logger = testLogger)

        assertTrue("Expected Real status", result.status is ConstellationBoundariesStatus.Real)
        val realStatus = result.status as ConstellationBoundariesStatus.Real
        assertTrue("Expected positive file size", realStatus.metadata.sizeBytes > 0)
        assertTrue("Expected success log", testLogger.infoMessages.any { it.contains("loaded successfully") })
        assertTrue("Expected no error logs", testLogger.errorMessages.isEmpty())
    }

    @Test
    fun `point inside polygon returns constellation code`() {
        val binary = builder.build(mapOf(
            "ORI" to listOf(listOf(80.0 to -10.0, 80.0 to 10.0, 100.0 to 10.0, 100.0 to -10.0)),
        ))
        val provider = ByteArrayAssetProvider(mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to binary))

        val result = BinaryConstellationBoundaries.load(provider)

        assertEquals("ORI", result.boundaries.findByEq(Equatorial(90.0, 0.0)))
        assertNull(result.boundaries.findByEq(Equatorial(50.0, 0.0)))
    }

    @Test
    fun `polygon crossing ra wrap`() {
        val wrapPolygon = listOf(350.0 to -5.0, 360.0 to -5.0, 10.0 to -5.0, 10.0 to 5.0, 360.0 to 5.0, 350.0 to 5.0)
        val binary = builder.build(mapOf("PSC" to listOf(wrapPolygon)))
        val provider = ByteArrayAssetProvider(mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to binary))

        val result = BinaryConstellationBoundaries.load(provider)

        assertEquals("PSC", result.boundaries.findByEq(Equatorial(359.0, 0.0)))
        assertEquals("PSC", result.boundaries.findByEq(Equatorial(1.0, 0.0)))
        assertNull(result.boundaries.findByEq(Equatorial(45.0, 0.0)))
    }

    @Test
    fun `known star mappings Betelgeuse Vega Polaris`() {
        val binary = builder.build(mapOf(
            "ORI" to listOf(listOf(70.0 to -15.0, 70.0 to 15.0, 100.0 to 15.0, 100.0 to -15.0)),
            "LYR" to listOf(listOf(260.0 to 20.0, 260.0 to 60.0, 300.0 to 60.0, 300.0 to 20.0)),
            "UMI" to listOf(listOf(0.0 to 70.0, 0.0 to 90.0, 60.0 to 90.0, 60.0 to 70.0)),
        ))
        val provider = ByteArrayAssetProvider(mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to binary))

        val result = BinaryConstellationBoundaries.load(provider)
        val b = result.boundaries

        assertEquals("ORI", b.findByEq(Equatorial(88.793, 7.407)))   // Betelgeuse
        assertEquals("LYR", b.findByEq(Equatorial(279.234, 38.783))) // Vega
        assertEquals("UMI", b.findByEq(Equatorial(37.954, 89.264)))  // Polaris
    }

    @Test
    fun `invalid header returns fake boundaries with clear reason`() {
        val provider = ByteArrayAssetProvider(mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to byteArrayOf(1, 2, 3, 4, 5)))
        val testLogger = TestLogger()

        val result = BinaryConstellationBoundaries.load(provider, logger = testLogger)

        assertTrue("Expected Fake status", result.status is ConstellationBoundariesStatus.Fake)
        val fakeStatus = result.status as ConstellationBoundariesStatus.Fake
        assertNotNull(fakeStatus.reason)
        assertTrue(fakeStatus.reason.contains("Invalid header"))
        assertEquals(1, testLogger.errorMessages.size)
        assertTrue(testLogger.errorMessages[0].contains("falling back to FakeConstellationBoundaries"))
    }

    @Test
    fun `wrong magic returns fake boundaries with clear reason`() {
        val payload = builder.buildPayloadOnly(mapOf("ORI" to listOf(listOf(80.0 to -10.0, 100.0 to 10.0))))
        val crc = CRC32().apply { update(payload) }.value.toInt()
        val header = buildHeader(magic = "FAKEWRNG", version = 1, constCount = 1, polyCount = 1, vertexCount = 4, crc = crc)
        val provider = ByteArrayAssetProvider(mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to header + payload))
        val testLogger = TestLogger()

        val result = BinaryConstellationBoundaries.load(provider, logger = testLogger)

        assertTrue(result.status is ConstellationBoundariesStatus.Fake)
        assertTrue((result.status as ConstellationBoundariesStatus.Fake).reason.contains("Invalid header"))
        assertEquals(1, testLogger.errorMessages.size)
    }

    @Test
    fun `unsupported version returns fake boundaries with clear reason`() {
        val payload = builder.buildPayloadOnly(mapOf("ORI" to listOf(listOf(80.0 to -10.0, 100.0 to 10.0))))
        val crc = CRC32().apply { update(payload) }.value.toInt()
        val header = buildHeader(magic = "PTSKCONS", version = 99, constCount = 1, polyCount = 1, vertexCount = 4, crc = crc)
        val provider = ByteArrayAssetProvider(mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to header + payload))
        val testLogger = TestLogger()

        val result = BinaryConstellationBoundaries.load(provider, logger = testLogger)

        assertTrue(result.status is ConstellationBoundariesStatus.Fake)
        val reason = (result.status as ConstellationBoundariesStatus.Fake).reason
        assertTrue(reason.contains("Unsupported catalog"))
        assertTrue(reason.contains("version=99"))
        assertEquals(1, testLogger.errorMessages.size)
    }

    @Test
    fun `crc mismatch returns fake boundaries with clear reason`() {
        val payload = builder.buildPayloadOnly(mapOf("ORI" to listOf(listOf(80.0 to -10.0, 100.0 to 10.0))))
        val header = buildHeader(magic = "PTSKCONS", version = 1, constCount = 1, polyCount = 1, vertexCount = 4, crc = 0)
        val provider = ByteArrayAssetProvider(mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to header + payload))
        val testLogger = TestLogger()

        val result = BinaryConstellationBoundaries.load(provider, logger = testLogger)

        assertTrue(result.status is ConstellationBoundariesStatus.Fake)
        val reason = (result.status as ConstellationBoundariesStatus.Fake).reason
        assertTrue(reason.contains("CRC mismatch"))
        assertTrue(reason.contains("expected=") && reason.contains("actual="))
    }

    @Test
    fun `file not found returns fake boundaries with clear reason`() {
        val provider = ByteArrayAssetProvider(emptyMap())
        val testLogger = TestLogger()

        val result = BinaryConstellationBoundaries.load(provider, logger = testLogger)

        assertTrue(result.status is ConstellationBoundariesStatus.Fake)
        assertTrue((result.status as ConstellationBoundariesStatus.Fake).reason.contains("Failed to open file"))
    }

    @Test
    fun `IOException during open returns fake boundaries`() {
        val provider = object : AssetProvider {
            override fun open(path: String): InputStream = throw IOException("Test IO error")
            override fun exists(path: String): Boolean = false
            override fun list(path: String): List<String> = emptyList()
        }

        val result = BinaryConstellationBoundaries.load(provider)

        assertTrue(result.status is ConstellationBoundariesStatus.Fake)
        assertTrue((result.status as ConstellationBoundariesStatus.Fake).reason.contains("Failed to open file"))
    }

    // Builds a 26-byte PTSKCONS header for error-case tests.
    private fun buildHeader(magic: String, version: Int, constCount: Int, polyCount: Int, vertexCount: Int, crc: Int): ByteArray {
        return ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(magic.padEnd(8).substring(0, 8).toByteArray(Charsets.US_ASCII))
            putShort(version.toShort())
            putShort(0)
            putShort(constCount.toShort())
            putInt(polyCount)
            putInt(vertexCount)
            putInt(crc)
        }.array()
    }

    private class TestLogger : Logger {
        val errorMessages = mutableListOf<String>()
        val infoMessages = mutableListOf<String>()
        override fun v(tag: String, msg: String, payload: Map<String, Any?>) = Unit
        override fun d(tag: String, msg: String, payload: Map<String, Any?>) = Unit
        override fun i(tag: String, msg: String, payload: Map<String, Any?>) { infoMessages.add("$tag: $msg $payload") }
        override fun w(tag: String, msg: String, err: Throwable?, payload: Map<String, Any?>) = Unit
        override fun e(tag: String, msg: String, err: Throwable?, payload: Map<String, Any?>) { errorMessages.add("$tag: $msg ${err?.message ?: ""} $payload") }
        override fun event(name: String, payload: Map<String, Any?>) = Unit
    }

    /**
     * Builds valid PTSKCONS polygon binaries for use in tests.
     * Iterates over all IAU codes so the header counts match the payload.
     */
    private class TestBinaryBuilder {

        fun build(polygonsByCode: Map<String, List<List<Pair<Double, Double>>>>): ByteArray {
            val payload = buildPayloadOnly(polygonsByCode)
            val crc = CRC32().apply { update(payload) }.value.toInt()

            val constellations = IAU_CODES.filter { code ->
                polygonsByCode.containsKey(code) || polygonsByCode.isEmpty()
            }.let { _ -> IAU_CODES } // always all codes so indices match

            val polyCount = IAU_CODES.sumOf { code -> (polygonsByCode[code] ?: emptyList()).sumOf { ring -> 1 } }
            val vertexCount = IAU_CODES.sumOf { code ->
                (polygonsByCode[code] ?: emptyList()).sumOf { ring -> ensureClosed(ring).size }
            }

            val header = ByteBuffer.allocate(26).order(ByteOrder.LITTLE_ENDIAN).apply {
                put(MAGIC.toByteArray(Charsets.US_ASCII))
                putShort(VERSION.toShort())
                putShort(0)
                putShort(constellations.size.toShort())
                putInt(polyCount)
                putInt(vertexCount)
                putInt(crc)
            }.array()

            return header + payload
        }

        /** Builds the payload (directory + polygons + vertices) without the header, for CRC/header tests. */
        fun buildPayloadOnly(polygonsByCode: Map<String, List<List<Pair<Double, Double>>>>): ByteArray {
            val dirOut = java.io.ByteArrayOutputStream()
            val polyOut = java.io.ByteArrayOutputStream()
            val vertOut = java.io.ByteArrayOutputStream()
            var polyIndex = 0
            var vertIndex = 0

            for (code in IAU_CODES) {
                val rings = (polygonsByCode[code] ?: emptyList()).map { ensureClosed(it) }
                val polyStart = polyIndex
                for (ring in rings) {
                    val aabb = computeAabb(ring)
                    polyOut.writeIntLE(vertIndex)
                    polyOut.writeIntLE(ring.size)
                    polyOut.writeFloatLE(aabb.raMin)
                    polyOut.writeFloatLE(aabb.raMax)
                    polyOut.writeFloatLE(aabb.decMin)
                    polyOut.writeFloatLE(aabb.decMax)
                    for ((ra, dec) in ring) {
                        vertOut.writeFloatLE(ra.toFloat())
                        vertOut.writeFloatLE(dec.toFloat())
                    }
                    vertIndex += ring.size
                    polyIndex++
                }
                val constAabb = if (rings.isEmpty()) Aabb(0f, 0f, 0f, 0f)
                    else mergeAabb(rings.map { computeAabb(it) })
                val codeBytes = code.padEnd(3).substring(0, 3).toByteArray(Charsets.US_ASCII)
                dirOut.write(codeBytes)
                dirOut.write(byteArrayOf(0))
                dirOut.writeIntLE(polyStart)
                dirOut.writeIntLE(rings.size)
                dirOut.writeFloatLE(constAabb.raMin)
                dirOut.writeFloatLE(constAabb.raMax)
                dirOut.writeFloatLE(constAabb.decMin)
                dirOut.writeFloatLE(constAabb.decMax)
            }

            return dirOut.toByteArray() + polyOut.toByteArray() + vertOut.toByteArray()
        }

        private fun ensureClosed(ring: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
            if (ring.isEmpty()) return ring
            val first = ring.first()
            val last = ring.last()
            return if (Math.abs(first.first - last.first) < 1e-6 && Math.abs(first.second - last.second) < 1e-6) ring
            else ring + first
        }

        private fun computeAabb(ring: List<Pair<Double, Double>>): Aabb {
            val decMin = ring.minOf { it.second }.toFloat()
            val decMax = ring.maxOf { it.second }.toFloat()
            val ras = ring.map { normalizeRa(it.first) }.sorted()
            var maxGap = -1.0; var gapIdx = 0
            for (i in ras.indices) {
                val gap = if (i == ras.lastIndex) ras[0] + 360.0 - ras[i] else ras[i + 1] - ras[i]
                if (gap > maxGap) { maxGap = gap; gapIdx = i }
            }
            val raMin = ras[(gapIdx + 1) % ras.size].toFloat()
            val raMax = ras[gapIdx].toFloat()
            return Aabb(raMin, raMax, decMin, decMax)
        }

        private fun mergeAabb(bounds: List<Aabb>): Aabb {
            return computeAabb(bounds.flatMap { listOf(it.raMin.toDouble() to it.decMin.toDouble(), it.raMax.toDouble() to it.decMax.toDouble()) })
        }

        private fun normalizeRa(value: Double): Double {
            var ra = value % 360.0
            if (ra < 0) ra += 360.0
            return if (ra == 360.0) 0.0 else ra
        }

        private fun java.io.ByteArrayOutputStream.writeIntLE(v: Int) =
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())

        private fun java.io.ByteArrayOutputStream.writeFloatLE(v: Float) =
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array())

        private data class Aabb(val raMin: Float, val raMax: Float, val decMin: Float, val decMax: Float)

        companion object {
            private const val MAGIC = "PTSKCONS"
            private const val VERSION = 1
            private val IAU_CODES = listOf(
                "AND", "ANT", "APS", "AQL", "AQR", "ARA", "ARI", "AUR", "BOO",
                "CAE", "CAM", "CAP", "CAR", "CAS", "CEN", "CEP", "CET",
                "CHA", "CIR", "CMA", "CMI", "CNC", "COL", "COM", "CRA",
                "CRB", "CRT", "CRU", "CRV", "CVN", "CYG", "DEL", "DOR",
                "DRA", "EQU", "ERI", "FOR", "GEM", "GRU", "HER", "HOR",
                "HYA", "HYI", "IND", "LAC", "LEO", "LEP", "LIB", "LMI",
                "LUP", "LYN", "LYR", "MEN", "MIC", "MON", "MUS", "NOR",
                "OCT", "OPH", "ORI", "PAV", "PEG", "PER", "PHE", "PIC",
                "PSA", "PSC", "PUP", "PYX", "RET", "SCL", "SCO", "SCT",
                "SER", "SEX", "SGE", "SGR", "TAU", "TEL", "TRA", "TRI",
                "TUC", "UMA", "UMI", "VEL", "VIR", "VOL", "VUL",
            )
        }
    }
}
