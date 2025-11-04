package dev.pointtosky.core.catalog.binary

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.catalog.io.AssetProvider
import dev.pointtosky.core.catalog.star.FakeStarCatalog
import dev.pointtosky.core.catalog.star.StarCatalog
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.math.floor
import kotlin.text.Charsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BinaryStarCatalogTest {

    @Test
    fun `load star catalog from valid binary`() {
        val records = listOf(
            TestStar(
                hip = 100,
                raDeg = 42.0,
                decDeg = -10.0,
                mag = 2.0,
                name = "Test Star",
                designation = "Alpha TST",
                constellation = "Tuc",
            ),
        )
        val catalog = BinaryStarCatalog.load(inMemory(records))

        assertTrue(catalog is BinaryStarCatalog)
        val matches = catalog.nearby(Equatorial(42.0, -10.0), radiusDeg = 1.0, magLimit = 3.0)
        assertEquals(1, matches.size)
        val star = matches.first()
        assertEquals(100, star.id)
        assertEquals("Test Star", star.name)
        assertEquals("ALPHA", star.bayer)
        assertEquals("TUC", star.constellation)
    }

    @Test
    fun `fallback is used when CRC mismatches`() {
        val binary = buildBinary(listOf(TestStar(hip = 1, raDeg = 0.0, decDeg = 0.0, mag = 1.0)))
        binary[binary.lastIndex] = binary.last().inv()
        val provider = InMemoryAssetProvider(mapOf(BinaryStarCatalog.DEFAULT_PATH to binary))

        val catalog = BinaryStarCatalog.load(provider)

        assertSame(FakeStarCatalog::class, catalog::class)
    }

    @Test
    fun `nearby handles RA wrap around`() {
        val records = listOf(
            TestStar(hip = 1, raDeg = 358.0, decDeg = 1.0, mag = 2.5),
            TestStar(hip = 2, raDeg = 2.0, decDeg = -1.0, mag = 2.0),
            TestStar(hip = 3, raDeg = 180.0, decDeg = 0.0, mag = 1.0),
        )
        val catalog = BinaryStarCatalog.load(inMemory(records))

        val matches = catalog.nearby(Equatorial(359.0, 0.0), radiusDeg = 5.0, magLimit = 6.0)

        assertEquals(setOf(1, 2), matches.map { it.id }.toSet())
    }

    @Test
    fun `brighter stars precede fainter ones when separation equal`() {
        val records = listOf(
            TestStar(hip = 1, raDeg = 10.0, decDeg = 0.0, mag = 2.0),
            TestStar(hip = 2, raDeg = 10.0, decDeg = 0.0, mag = 1.0),
        )
        val catalog = BinaryStarCatalog.load(inMemory(records))

        val matches = catalog.nearby(Equatorial(10.0, 0.0), radiusDeg = 0.5, magLimit = 5.0)

        assertEquals(listOf(2, 1), matches.map { it.id })
    }

    @Test
    fun `cli generated artifacts are readable`() {
        val directory = System.getProperty("catalog.bin.dir")?.takeIf { it.isNotBlank() } ?: return assumeTrue(false)
        val root = Path.of(directory)
        assumeTrue("stars_v1.bin not found", Files.exists(root.resolve("stars_v1.bin")))
        val provider = DirectoryAssetProvider(root)

        val catalog = BinaryStarCatalog.load(provider, path = "stars_v1.bin")

        assertTrue(catalog is StarCatalog)
        val matches = catalog.nearby(Equatorial(101.0, -16.7), radiusDeg = 5.0, magLimit = 2.0)
        assertTrue(matches.isNotEmpty())
    }

    private fun inMemory(records: List<TestStar>): AssetProvider {
        val binary = buildBinary(records)
        return InMemoryAssetProvider(mapOf(BinaryStarCatalog.DEFAULT_PATH to binary))
    }

    private data class TestStar(
        val hip: Int,
        val raDeg: Double,
        val decDeg: Double,
        val mag: Double,
        val name: String? = null,
        val designation: String? = null,
        val constellation: String? = null,
    )

    private class InMemoryAssetProvider(
        private val entries: Map<String, ByteArray>,
    ) : AssetProvider {
        override fun open(path: String): InputStream {
            val bytes = entries[path] ?: throw IllegalArgumentException("No asset for $path")
            return ByteArrayInputStream(bytes)
        }

        override fun exists(path: String): Boolean = entries.containsKey(path)
    }

    private class DirectoryAssetProvider(
        private val root: Path,
    ) : AssetProvider {
        override fun open(path: String): InputStream = Files.newInputStream(root.resolve(path))

        override fun exists(path: String): Boolean = Files.exists(root.resolve(path))
    }

    private fun buildBinary(records: List<TestStar>): ByteArray {
        val pool = StringPoolBuilder()
        val recordBuffer = ByteBuffer.allocate(records.size * STAR_RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        records.forEach { record ->
            val ra = record.raDeg.toFloat()
            val dec = record.decDeg.toFloat()
            val mag = record.mag.toFloat()
            val hip = record.hip
            val nameOffset = pool.offsetOf(record.name)
            val designationOffset = pool.offsetOf(record.designation)
            val flags = buildFlags(record)
            val conIndex = record.constellation?.let { CONSTELLATION_INDEX[it.uppercase()] } ?: -1

            recordBuffer.putFloat(ra)
            recordBuffer.putFloat(dec)
            recordBuffer.putFloat(mag)
            recordBuffer.putFloat(0.0f)
            recordBuffer.putInt(hip)
            recordBuffer.putInt(nameOffset)
            recordBuffer.putInt(designationOffset)
            recordBuffer.putShort(flags.toShort())
            recordBuffer.putShort(conIndex.toShort())
        }
        val recordBytes = recordBuffer.array()
        val stringPoolBytes = pool.toByteArray()

        val indexBytes = buildIndex(records)
        val dataBytes = stringPoolBytes + recordBytes + indexBytes
        val crc = CRC32().apply { update(dataBytes) }.value.toInt()
        val header = buildHeader(
            starCount = records.size,
            stringPoolSize = stringPoolBytes.size,
            indexOffset = stringPoolBytes.size + recordBytes.size,
            indexSize = indexBytes.size,
            crc32 = crc,
        )
        return header + dataBytes
    }

    private fun buildFlags(record: TestStar): Int {
        var flags = 0
        if (!record.name.isNullOrBlank()) flags = flags or FLAG_HAS_NAME
        if (!record.designation.isNullOrBlank()) flags = flags or FLAG_HAS_DESIGNATION
        if (record.hip > 0) flags = flags or FLAG_HAS_HIP
        return flags
    }

    private fun buildIndex(records: List<TestStar>): ByteArray {
        val buckets = Array(BAND_COUNT) { mutableListOf<Pair<Int, Float>>() }
        records.forEachIndexed { index, record ->
            val bandId = floor(record.decDeg).toInt().coerceIn(-90, 89)
            val normalizedRa = ((record.raDeg % 360.0) + 360.0) % 360.0
            val arrayIndex = bandId - MIN_BAND_ID
            buckets[arrayIndex].add(index to normalizedRa.toFloat())
        }

        val bandOffsets = IntArray(BAND_COUNT)
        val bandCounts = IntArray(BAND_COUNT)
        val starIds = ArrayList<Int>()
        val raValues = ArrayList<Float>()
        var cursor = 0

        buckets.forEachIndexed { bandIndex, entries ->
            entries.sortBy { it.second }
            bandOffsets[bandIndex] = cursor
            bandCounts[bandIndex] = entries.size
            cursor += entries.size
            entries.forEach { (starIndex, ra) ->
                starIds += starIndex
                raValues += ra
            }
        }

        val totalEntries = starIds.size
        val bandTableSize = BAND_COUNT * (Short.SIZE_BYTES + Int.SIZE_BYTES * 2)
        val idsSize = totalEntries * Int.SIZE_BYTES
        val rasSize = totalEntries * java.lang.Float.BYTES
        val summarySize = SUMMARY_FLOATS * java.lang.Float.BYTES
        val buffer = ByteBuffer.allocate(bandTableSize + idsSize + rasSize + summarySize).order(ByteOrder.LITTLE_ENDIAN)

        for (band in 0 until BAND_COUNT) {
            val bandId = (band + MIN_BAND_ID).toShort()
            buffer.putShort(bandId)
            buffer.putInt(bandOffsets[band])
            buffer.putInt(bandCounts[band])
        }

        starIds.forEach { buffer.putInt(it) }
        raValues.forEach { buffer.putFloat(it) }

        // Summary block (optional)
        buffer.putFloat(BAND_COUNT.toFloat())
        buffer.putFloat(totalEntries.toFloat())
        val mags = records.map { it.mag }
        buffer.putFloat(mags.minOrNull()?.toFloat() ?: 0f)
        buffer.putFloat(mags.maxOrNull()?.toFloat() ?: 0f)
        val raValuesAll = records.map { it.raDeg }
        buffer.putFloat(raValuesAll.minOrNull()?.toFloat() ?: 0f)
        buffer.putFloat(raValuesAll.maxOrNull()?.toFloat() ?: 0f)
        val decValues = records.map { it.decDeg }
        buffer.putFloat(decValues.minOrNull()?.toFloat() ?: 0f)
        buffer.putFloat(decValues.maxOrNull()?.toFloat() ?: 0f)

        return buffer.array()
    }

    private fun buildHeader(
        starCount: Int,
        stringPoolSize: Int,
        indexOffset: Int,
        indexSize: Int,
        crc32: Int,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(MAGIC)
        buffer.putShort(VERSION.toShort())
        buffer.putShort(0)
        buffer.putInt(starCount)
        buffer.putInt(stringPoolSize)
        buffer.putInt(indexOffset)
        buffer.putInt(indexSize)
        buffer.putInt(crc32)
        return buffer.array()
    }

    private class StringPoolBuilder {
        private val buffer = ByteArrayOutputStream()
        private val offsets = mutableMapOf<String, Int>()

        init {
            buffer.write(0)
        }

        fun offsetOf(value: String?): Int {
            if (value.isNullOrBlank()) return 0
            return offsets.getOrPut(value) {
                val bytes = value.toByteArray(Charsets.UTF_8)
                val offset = buffer.size()
                buffer.write(bytes)
                buffer.write(0)
                offset
            }
        }

        fun toByteArray(): ByteArray = buffer.toByteArray()
    }

    companion object {
        private const val HEADER_SIZE = 32
        private const val STAR_RECORD_SIZE = 32
        private const val BAND_COUNT = 180
        private const val SUMMARY_FLOATS = 8
        private const val MIN_BAND_ID = -90
        private const val FLAG_HAS_NAME = 0x01
        private const val FLAG_HAS_DESIGNATION = 0x02
        private const val FLAG_HAS_HIP = 0x04
        private val MAGIC = "PTSKSTAR".toByteArray(Charsets.US_ASCII)
        private const val VERSION = 1
        private val CONSTELLATION_INDEX: Map<String, Int> = arrayOf(
            "And", "Ant", "Aps", "Aql", "Aqr", "Ara", "Ari", "Aur",
            "Boo", "Cae", "Cam", "Cap", "Car", "Cas", "Cen", "Cep",
            "Cet", "Cha", "Cir", "CMa", "CMi", "Cnc", "Col", "Com",
            "CrA", "CrB", "Crt", "Cru", "Crv", "CVn", "Cyg", "Del",
            "Dor", "Dra", "Equ", "Eri", "For", "Gem", "Gru", "Her",
            "Hor", "Hya", "Hyi", "Ind", "Lac", "Leo", "Lep", "Lib",
            "Lup", "Lyn", "Lyr", "Men", "Mic", "Mon", "Mus", "Nor",
            "Oct", "Oph", "Ori", "Pav", "Peg", "Per", "Phe", "Pic",
            "PsA", "Psc", "Pup", "Pyx", "Ret", "Scl", "Sco", "Sct",
            "Ser", "Sex", "Sge", "Sgr", "Tau", "Tel", "TrA", "Tri",
            "Tuc", "UMa", "UMi", "Vel", "Vir", "Vol", "Vul"
        ).mapIndexed { index, code -> code.uppercase() to index }.toMap()
    }
}
