package dev.pointtosky.core.catalog.binary

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.catalog.fake.FakeSkyCatalog
import dev.pointtosky.core.catalog.io.AssetProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.text.Charsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BinaryStarCatalogTest {
    @Test
    fun `load star catalog from valid binary`() {
        val record = StarRecord(id = "1", name = "Alpha", raDeg = 10.0, decDeg = 5.0, magnitude = 2.0)
        val binary = buildStarBinary(listOf(record))
        val provider = InMemoryAssetProvider(mapOf(BinaryStarCatalog.DEFAULT_PATH to binary))

        val catalog = BinaryStarCatalog.load(provider)

        assertTrue(catalog is BinaryStarCatalog)
        val matches = catalog.nearby(Equatorial(10.0, 5.0), radiusDeg = 1.0, magLimit = 3.0)
        assertEquals(1, matches.size)
        assertEquals("1", matches.first().id)
    }

    @Test
    fun `fallback is used when CRC mismatches`() {
        val record = StarRecord(id = "1", name = null, raDeg = 0.0, decDeg = 0.0, magnitude = 1.0)
        val binary = buildStarBinary(listOf(record)).clone()
        // Corrupt payload byte to trigger CRC mismatch.
        binary[BinaryCatalogHeader.HEADER_SIZE_BYTES] = binary[BinaryCatalogHeader.HEADER_SIZE_BYTES].xor(0xFF.toByte())
        val provider = InMemoryAssetProvider(mapOf(BinaryStarCatalog.DEFAULT_PATH to binary))

        val catalog = BinaryStarCatalog.load(provider)

        assertSame(FakeSkyCatalog, catalog)
    }

    @Test
    fun `cli generated artifacts are readable`() {
        val directory = System.getProperty("catalog.bin.dir")?.takeIf { it.isNotBlank() } ?: return assumeTrue(false)
        val root = Path.of(directory)
        assumeTrue("stars_v1.bin not found", Files.exists(root.resolve("stars_v1.bin")))
        val provider = DirectoryAssetProvider(root)

        val catalog = BinaryStarCatalog.load(provider, path = "stars_v1.bin")

        assertTrue(catalog is BinaryStarCatalog)
        val matches = catalog.nearby(Equatorial(101.0, -16.7), radiusDeg = 5.0, magLimit = 2.0)
        assertTrue(matches.any { it.id == "HIP32349" })
    }

    private data class StarRecord(
        val id: String,
        val name: String?,
        val raDeg: Double,
        val decDeg: Double,
        val magnitude: Double?,
    )

    private fun buildStarBinary(records: List<StarRecord>): ByteArray {
        val payload = LittleEndianByteArray()
        records.forEach { record ->
            payload.writeString(record.id)
            payload.writeNullableString(record.name)
            payload.writeDouble(record.raDeg)
            payload.writeDouble(record.decDeg)
            payload.writeFloat(record.magnitude?.toFloat() ?: Float.NaN)
        }
        val payloadBytes = payload.toByteArray()
        val crc = CRC32().apply { update(payloadBytes) }.value.toInt()
        val header = LittleEndianByteArray()
        header.writeAscii("PTSK")
        header.writeAscii("STAR")
        header.writeInt(1)
        header.writeInt(records.size)
        header.writeInt(crc)
        return header.toByteArray() + payloadBytes
    }

    private class LittleEndianByteArray {
        private val buffer = mutableListOf<Byte>()

        fun writeAscii(value: String) {
            require(value.length == 4) { "ASCII field must be 4 characters" }
            value.toByteArray(Charsets.US_ASCII).forEach { buffer += it }
        }

        fun writeInt(value: Int) {
            repeat(4) { index ->
                buffer += ((value shr (8 * index)) and 0xFF).toByte()
            }
        }

        fun writeDouble(value: Double) {
            val bits = java.lang.Double.doubleToRawLongBits(value)
            repeat(8) { index ->
                buffer += ((bits shr (8 * index)) and 0xFF).toByte()
            }
        }

        fun writeFloat(value: Float) {
            val bits = java.lang.Float.floatToRawIntBits(value)
            repeat(4) { index ->
                buffer += ((bits shr (8 * index)) and 0xFF).toByte()
            }
        }

        fun writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            buffer.addAll(bytes.toList())
        }

        fun writeNullableString(value: String?) {
            if (value == null) {
                writeInt(-1)
                return
            }
            writeString(value)
        }

        fun toByteArray(): ByteArray = buffer.toByteArray()
    }

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
}

private fun Byte.xor(other: Byte): Byte = (this.toInt() xor other.toInt()).toByte()
