package dev.pointtosky.core.catalog.constellation

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.catalog.io.AssetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import kotlin.math.abs

class BinaryConstellationBoundariesTest {
    private val builder = TestBinaryBuilder()

    @Test
    fun `point inside simple polygon`() {
        val polygons = mapOf(
            "ORI" to listOf(
                listOf(
                    80.0 to -10.0,
                    80.0 to 10.0,
                    100.0 to 10.0,
                    100.0 to -10.0,
                ),
            ),
        )
        val binary = builder.build(polygons)
        val boundaries = BinaryConstellationBoundaries(InMemoryAssetProvider(binary))
        assertEquals("ORI", boundaries.findByEq(Equatorial(88.0, 0.0)))
        assertNull(boundaries.findByEq(Equatorial(50.0, 0.0)))
    }

    @Test
    fun `polygon crossing ra wrap`() {
        val wrapPolygon = listOf(
            350.0 to -5.0,
            360.0 to -5.0,
            0.0 to -5.0,
            0.0 to 5.0,
            360.0 to 5.0,
            350.0 to 5.0,
        )
        val polygons = mapOf(
            "PSC" to listOf(wrapPolygon),
        )
        val binary = builder.build(polygons)
        val boundaries = BinaryConstellationBoundaries(InMemoryAssetProvider(binary))
        assertEquals("PSC", boundaries.findByEq(Equatorial(359.0, 0.0)))
        assertEquals("PSC", boundaries.findByEq(Equatorial(1.0, 0.0)))
        assertNull(boundaries.findByEq(Equatorial(45.0, 0.0)))
    }

    @Test
    fun `known stars mapping`() {
        val polygons = mapOf(
            "ORI" to listOf(
                listOf(
                    70.0 to -15.0,
                    70.0 to 15.0,
                    100.0 to 15.0,
                    100.0 to -15.0,
                ),
            ),
            "LYR" to listOf(
                listOf(
                    260.0 to 20.0,
                    260.0 to 60.0,
                    300.0 to 60.0,
                    300.0 to 20.0,
                ),
            ),
            "UMI" to listOf(
                listOf(
                    0.0 to 70.0,
                    0.0 to 90.0,
                    60.0 to 90.0,
                    60.0 to 70.0,
                ),
            ),
        )
        val binary = builder.build(polygons)
        val boundaries = BinaryConstellationBoundaries(InMemoryAssetProvider(binary))
        assertEquals("ORI", boundaries.findByEq(Equatorial(88.793, 7.407))) // Betelgeuse
        assertEquals("LYR", boundaries.findByEq(Equatorial(279.234, 38.783))) // Vega
        assertEquals("UMI", boundaries.findByEq(Equatorial(37.954, 89.264))) // Polaris
    }

    private class InMemoryAssetProvider(private val data: ByteArray) : AssetProvider {
        override fun open(path: String): InputStream = ByteArrayInputStream(data)
        override fun exists(path: String): Boolean = true
    }

    private class TestBinaryBuilder {
        fun build(polygonsByCode: Map<String, List<List<Pair<Double, Double>>>>): ByteArray {
            val directories = mutableListOf<DirectoryEntry>()
            val polygonEntries = mutableListOf<PolygonEntry>()
            val vertices = mutableListOf<Float>()
            var polygonIndex = 0
            var vertexIndex = 0

            for (code in IAU_CODES) {
                val polygons = polygonsByCode[code] ?: emptyList()
                val polygonStart = polygonIndex
                val polygonCount = polygons.size
                val polygonBounds = mutableListOf<Aabb>()
                for (polygon in polygons) {
                    val ring = ensureClosed(polygon)
                    val aabb = computeAabb(ring)
                    polygonBounds += aabb
                    polygonEntries += PolygonEntry(vertexIndex, ring.size, aabb)
                    for ((ra, dec) in ring) {
                        vertices += ra.toFloat()
                        vertices += dec.toFloat()
                    }
                    vertexIndex += ring.size
                    polygonIndex += 1
                }
                val constAabb = mergeAabb(polygonBounds)
                directories += DirectoryEntry(code, polygonStart, polygonCount, constAabb)
            }

            val directoryBytes = ByteArrayOutputStream()
            val polygonBytes = ByteArrayOutputStream()
            val vertexBytes = ByteArrayOutputStream()

            directories.forEach { entry ->
                writeDirectoryEntry(directoryBytes, entry)
            }
            polygonEntries.forEach { entry ->
                writePolygonEntry(polygonBytes, entry)
            }
            vertices.forEach { value ->
                vertexBytes.writeFloatLE(value)
            }

            val dataBytes = directoryBytes.toByteArray() + polygonBytes.toByteArray() + vertexBytes.toByteArray()
            val crc = CRC32().apply { update(dataBytes) }.value.toInt()

            val header = ByteArrayOutputStream()
            header.write(MAGIC.toByteArray(Charsets.US_ASCII))
            header.writeShortLE(VERSION)
            header.writeShortLE(0)
            header.writeShortLE(IAU_CODES.size)
            header.writeIntLE(polygonEntries.size)
            header.writeIntLE(vertices.size / 2)
            header.writeIntLE(crc)

            return header.toByteArray() + dataBytes
        }

        private fun ensureClosed(vertices: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
            if (vertices.isEmpty()) return vertices
            val first = vertices.first()
            val last = vertices.last()
            return if (approximatelyEqual(first.first, last.first) && approximatelyEqual(first.second, last.second)) {
                vertices
            } else {
                vertices + first
            }
        }

        private fun computeAabb(vertices: List<Pair<Double, Double>>): Aabb {
            if (vertices.isEmpty()) return Aabb(0f, 0f, 0f, 0f)
            val decValues = vertices.map { it.second }
            val decMin = decValues.minOrNull() ?: 0.0
            val decMax = decValues.maxOrNull() ?: 0.0
            val circular = computeCircularBounds(vertices.map { normalizeRa(it.first) })
            return Aabb(circular.first.toFloat(), circular.second.toFloat(), decMin.toFloat(), decMax.toFloat())
        }

        private fun mergeAabb(bounds: List<Aabb>): Aabb {
            if (bounds.isEmpty()) return Aabb(0f, 0f, 0f, 0f)
            val decMin = bounds.minOf { it.decMin }
            val decMax = bounds.maxOf { it.decMax }
            val circular = computeCircularBounds(bounds.flatMap { listOf(it.raMin.toDouble(), it.raMax.toDouble()) })
            return Aabb(circular.first.toFloat(), circular.second.toFloat(), decMin, decMax)
        }

        private fun computeCircularBounds(values: List<Double>): Pair<Double, Double> {
            if (values.isEmpty()) return 0.0 to 0.0
            val normalized = values.map { normalizeRa(it) }.sorted()
            var maxGap = -1.0
            var gapIndex = 0
            for (i in normalized.indices) {
                val current = normalized[i]
                val next = normalized[(i + 1) % normalized.size]
                val diff = if (i == normalized.lastIndex) next + 360.0 - current else next - current
                if (diff > maxGap) {
                    maxGap = diff
                    gapIndex = i
                }
            }
            val startIndex = (gapIndex + 1) % normalized.size
            val min = normalized[startIndex]
            val max = normalized[gapIndex]
            return min to max
        }

        private fun normalizeRa(value: Double): Double {
            var ra = value % 360.0
            if (ra < 0) ra += 360.0
            return if (approximatelyEqual(ra, 360.0)) 0.0 else ra
        }

        private fun approximatelyEqual(a: Double, b: Double): Boolean = abs(a - b) < 1e-6

        private fun ByteArrayOutputStream.writeShortLE(value: Int) {
            write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
        }

        private fun ByteArrayOutputStream.writeIntLE(value: Int) {
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        }

        private fun ByteArrayOutputStream.writeFloatLE(value: Float) {
            write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
        }

        private fun writeDirectoryEntry(out: ByteArrayOutputStream, entry: DirectoryEntry) {
            val codeBytes = entry.code.padEnd(3, ' ').substring(0, 3).toByteArray(Charsets.US_ASCII)
            out.write(codeBytes)
            out.write(byteArrayOf(0))
            out.writeIntLE(entry.polyStart)
            out.writeIntLE(entry.polyCount)
            out.writeFloatLE(entry.aabb.raMin)
            out.writeFloatLE(entry.aabb.raMax)
            out.writeFloatLE(entry.aabb.decMin)
            out.writeFloatLE(entry.aabb.decMax)
        }

        private fun writePolygonEntry(out: ByteArrayOutputStream, entry: PolygonEntry) {
            out.writeIntLE(entry.vertexStart)
            out.writeIntLE(entry.vertexCount)
            out.writeFloatLE(entry.aabb.raMin)
            out.writeFloatLE(entry.aabb.raMax)
            out.writeFloatLE(entry.aabb.decMin)
            out.writeFloatLE(entry.aabb.decMax)
        }

        private data class DirectoryEntry(
            val code: String,
            val polyStart: Int,
            val polyCount: Int,
            val aabb: Aabb,
        )

        private data class PolygonEntry(
            val vertexStart: Int,
            val vertexCount: Int,
            val aabb: Aabb,
        )

        private data class Aabb(
            val raMin: Float,
            val raMax: Float,
            val decMin: Float,
            val decMax: Float,
        )
    }

    private class ByteArrayOutputStream : java.io.ByteArrayOutputStream()

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
