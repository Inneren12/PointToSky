package dev.pointtosky.core.catalog.binary

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.catalog.fake.FakeStarCatalog
import dev.pointtosky.core.catalog.io.AssetProvider
import dev.pointtosky.core.catalog.star.Star
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BinaryStarCatalogTest {

    @Test
    fun `nearby handles RA wrap across zero meridian`() {
        val stars = listOf(
            TestStar(raDeg = 359.2, decDeg = 0.1, mag = 1.8, hip = 1, designation = "Alpha", constellation = "ORI"),
            TestStar(raDeg = 0.8, decDeg = -0.2, mag = 2.1, hip = 2, designation = "Beta", constellation = "ORI"),
            TestStar(raDeg = 120.0, decDeg = 30.0, mag = 0.5, hip = 3, designation = "Gamma", constellation = "CMA"),
        )
        val binary = buildBinary(stars)
        val provider = InMemoryAssetProvider(mapOf(BinaryStarCatalog.DEFAULT_PATH to binary))

        val catalog = BinaryStarCatalog(provider)
        val result = catalog.nearby(Equatorial(359.5, 0.0), radiusDeg = 5.0, magLimit = 3.0)

        assertEquals(2, result.size)
        val ids = result.map(Star::id).toSet()
        assertTrue(ids.contains(1))
        assertTrue(ids.contains(2))
    }

    @Test
    fun `brighter star wins when separation ties`() {
        val stars = listOf(
            TestStar(raDeg = 0.0, decDeg = 1.0, mag = 1.0, hip = 10, designation = "Alpha", constellation = "ORI"),
            TestStar(raDeg = 0.0, decDeg = -1.0, mag = 2.5, hip = 11, designation = "Beta", constellation = "ORI"),
        )
        val binary = buildBinary(stars)
        val provider = InMemoryAssetProvider(mapOf(BinaryStarCatalog.DEFAULT_PATH to binary))

        val catalog = BinaryStarCatalog(provider)
        val result = catalog.nearby(Equatorial(0.0, 0.0), radiusDeg = 2.5, magLimit = 6.0)

        assertEquals(2, result.size)
        assertEquals(10, result.first().id)
    }

    @Test
    fun `fallback is returned when CRC mismatches`() {
        val stars = listOf(
            TestStar(raDeg = 10.0, decDeg = 5.0, mag = 2.0, hip = 42, designation = "Alpha", constellation = "ORI"),
        )
        val binary = buildBinary(stars).clone()
        binary[HEADER_SIZE] = binary[HEADER_SIZE].inc()
        val provider = InMemoryAssetProvider(mapOf(BinaryStarCatalog.DEFAULT_PATH to binary))
        val fallback = FakeStarCatalog()

        val catalog = BinaryStarCatalog.load(provider, fallback = fallback)

        assertSame(fallback, catalog)
    }

    private data class TestStar(
        val raDeg: Double,
        val decDeg: Double,
        val mag: Double,
        val hip: Int,
        val name: String? = null,
        val designation: String? = null,
        val constellation: String? = null,
    )

    private fun buildBinary(stars: List<TestStar>): ByteArray {
        val poolBuilder = StringPoolBuilder()
        stars.forEach { star ->
            poolBuilder.offset(star.name)
            poolBuilder.offset(star.designation)
        }
        val stringPool = poolBuilder.toByteArray()

        val recordsBuffer = ByteBuffer.allocate(stars.size * RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        stars.forEach { star ->
            recordsBuffer.putFloat(star.raDeg.toFloat())
            recordsBuffer.putFloat(star.decDeg.toFloat())
            recordsBuffer.putFloat(star.mag.toFloat())
            recordsBuffer.putFloat(0.0f) // BV placeholder
            recordsBuffer.putInt(star.hip)
            recordsBuffer.putInt(poolBuilder.offset(star.name))
            recordsBuffer.putInt(poolBuilder.offset(star.designation))
            recordsBuffer.putShort(computeFlags(star).toShort())
            recordsBuffer.putShort(constellationIndex(star.constellation).toShort())
        }
        val records = ByteArray(recordsBuffer.position())
        recordsBuffer.flip()
        recordsBuffer.get(records)

        val indexBytes = buildIndex(stars)

        val dataStream = ByteArrayOutputStream()
        dataStream.write(stringPool)
        dataStream.write(records)
        dataStream.write(indexBytes)
        val data = dataStream.toByteArray()

        val crc = CRC32().apply { update(data) }.value.toInt()

        val headerBuffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        headerBuffer.put(MAGIC)
        headerBuffer.putShort(1)
        headerBuffer.putShort(0)
        headerBuffer.putInt(stars.size)
        headerBuffer.putInt(stringPool.size)
        headerBuffer.putInt(stringPool.size + records.size)
        headerBuffer.putInt(indexBytes.size)
        headerBuffer.putInt(crc)

        val header = ByteArray(headerBuffer.position())
        headerBuffer.flip()
        headerBuffer.get(header)

        return header + data
    }

    private fun buildIndex(stars: List<TestStar>): ByteArray {
        val buckets = Array(180) { mutableListOf<Pair<Int, Float>>() }
        var minMag = Double.POSITIVE_INFINITY
        var maxMag = Double.NEGATIVE_INFINITY
        var minRa = Double.POSITIVE_INFINITY
        var maxRa = Double.NEGATIVE_INFINITY
        var minDec = Double.POSITIVE_INFINITY
        var maxDec = Double.NEGATIVE_INFINITY

        stars.forEachIndexed { index, star ->
            val band = (floor(star.decDeg).toInt().coerceIn(-90, 89) + 90)
            val raNorm = normalizeRa(star.raDeg).toFloat()
            buckets[band].add(index to raNorm)
            minMag = minOf(minMag, star.mag)
            maxMag = maxOf(maxMag, star.mag)
            minRa = minOf(minRa, raNorm.toDouble())
            maxRa = maxOf(maxRa, raNorm.toDouble())
            minDec = minOf(minDec, star.decDeg)
            maxDec = maxOf(maxDec, star.decDeg)
        }

        buckets.forEach { it.sortBy { pair -> pair.second } }

        val bandStarts = IntArray(180)
        val bandCounts = IntArray(180)
        var cursor = 0
        for (i in 0 until 180) {
            bandStarts[i] = cursor
            val count = buckets[i].size
            bandCounts[i] = count
            cursor += count
        }

        val starIds = IntArray(cursor)
        val raValues = FloatArray(cursor)
        var idx = 0
        for (i in 0 until 180) {
            for ((starIndex, ra) in buckets[i]) {
                starIds[idx] = starIndex
                raValues[idx] = ra
                idx += 1
            }
        }

        val summary = floatArrayOf(
            180f,
            cursor.toFloat(),
            minMag.toFloat(),
            maxMag.toFloat(),
            (if (minRa.isFinite()) minRa else 0.0).toFloat(),
            (if (maxRa.isFinite()) maxRa else 0.0).toFloat(),
            minDec.toFloat(),
            maxDec.toFloat(),
        )

        val buffer = ByteBuffer.allocate(180 * (2 + 4 + 4) + cursor * (4 + 4) + summary.size * 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 180) {
            buffer.putShort((i - 90).toShort())
            buffer.putInt(bandStarts[i])
            buffer.putInt(bandCounts[i])
        }
        starIds.forEach { buffer.putInt(it) }
        raValues.forEach { buffer.putFloat(it) }
        summary.forEach { buffer.putFloat(it) }

        val index = ByteArray(buffer.position())
        buffer.flip()
        buffer.get(index)
        return index
    }

    private fun computeFlags(star: TestStar): Int {
        var flags = 0
        if (!star.name.isNullOrBlank()) flags = flags or FLAG_HAS_NAME
        if (!star.designation.isNullOrBlank()) flags = flags or FLAG_HAS_DESIGNATION
        if (star.hip > 0) flags = flags or FLAG_HAS_HIP
        return flags
    }

    private fun normalizeRa(value: Double): Double {
        var result = value % 360.0
        if (result < 0) result += 360.0
        return result
    }

    private class StringPoolBuilder {
        private val offsets = mutableMapOf<String, Int>()
        private val buffer = ByteArrayOutputStream().apply { write(0) }

        fun offset(value: String?): Int {
            if (value.isNullOrBlank()) return 0
            return offsets.getOrPut(value) {
                val bytes = value.toByteArray(StandardCharsets.UTF_8)
                val offset = buffer.size()
                buffer.write(bytes)
                buffer.write(0)
                offset
            }
        }

        fun toByteArray(): ByteArray = buffer.toByteArray()
    }

    private class InMemoryAssetProvider(
        private val files: Map<String, ByteArray>,
    ) : AssetProvider {
        override fun open(path: String): InputStream {
            val bytes = files[path] ?: throw IllegalArgumentException("No asset for $path")
            return ByteArrayInputStream(bytes)
        }

        override fun exists(path: String): Boolean = files.containsKey(path)

        override fun list(path: String): List<String> = files.keys.filter { it.startsWith(path) }
    }

    private fun constellationIndex(code: String?): Int {
        if (code.isNullOrBlank()) return -1
        val normalized = code.uppercase()
        return CONSTELLATION_CODES.indexOf(normalized)
    }

    companion object {
        private const val FLAG_HAS_NAME = 0x01
        private const val FLAG_HAS_DESIGNATION = 0x02
        private const val FLAG_HAS_HIP = 0x04

        private const val RECORD_SIZE: Int = 32
        private const val HEADER_SIZE: Int = 32
        private val MAGIC: ByteArray = "PTSKSTAR".toByteArray(StandardCharsets.US_ASCII)
        private val CONSTELLATION_CODES = listOf(
            "AND", "ANT", "APS", "AQL", "AQR", "ARA", "ARI", "AUR",
            "BOO", "CAE", "CAM", "CAP", "CAR", "CAS", "CEN", "CEP",
            "CET", "CHA", "CIR", "CMA", "CMI", "CNC", "COL", "COM",
            "CRA", "CRB", "CRT", "CRU", "CRV", "CVN", "CYG", "DEL",
            "DOR", "DRA", "EQU", "ERI", "FOR", "GEM", "GRU", "HER",
            "HOR", "HYA", "HYI", "IND", "LAC", "LEO", "LEP", "LIB",
            "LUP", "LYN", "LYR", "MEN", "MIC", "MON", "MUS", "NOR",
            "OCT", "OPH", "ORI", "PAV", "PEG", "PER", "PHE", "PIC",
            "PSA", "PSC", "PUP", "PYX", "RET", "SCL", "SCO", "SCT",
            "SER", "SEX", "SGE", "SGR", "TAU", "TEL", "TRA", "TRI",
            "TUC", "UMA", "UMI", "VEL", "VIR", "VOL", "VUL",
        )
    }
}
