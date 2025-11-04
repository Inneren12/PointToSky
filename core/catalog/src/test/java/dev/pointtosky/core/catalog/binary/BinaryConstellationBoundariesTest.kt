package dev.pointtosky.core.catalog.binary

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.catalog.fake.FakeConstellationBoundaries
import dev.pointtosky.core.catalog.io.AssetProvider
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.text.Charsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BinaryConstellationBoundariesTest {
    private companion object {
        private const val HEADER_SIZE_BYTES: Int = 20
    }
    @Test
    fun `load constellation boundaries from valid binary`() {
        val region = RegionRecord(
            iauCode = "ORI",
            minRaDeg = 70.0,
            maxRaDeg = 110.0,
            minDecDeg = -30.0,
            maxDecDeg = 30.0,
        )
        val binary = buildConstellationBinary(listOf(region))
        val provider = InMemoryAssetProvider(mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to binary))

        val boundaries = BinaryConstellationBoundaries.load(provider)

        assertTrue(boundaries is BinaryConstellationBoundaries)
        assertEquals("ORI", boundaries.findByEq(Equatorial(90.0, 0.0)))
    }

    @Test
    fun `fallback is used when CRC mismatches`() {
        val region = RegionRecord("ORI", 0.0, 10.0, -5.0, 5.0)
        val binary = buildConstellationBinary(listOf(region)).clone()
        binary[HEADER_SIZE_BYTES] = binary[HEADER_SIZE_BYTES].xor(0xFF.toByte())
        val provider = InMemoryAssetProvider(mapOf(BinaryConstellationBoundaries.DEFAULT_PATH to binary))

        val boundaries = BinaryConstellationBoundaries.load(provider)

        assertTrue(boundaries === FakeConstellationBoundaries)
        assertNull(boundaries.findByEq(Equatorial(5.0, 0.0)))
    }

    @Test
    fun `cli generated constellation artifacts are readable`() {
        val directory = System.getProperty("catalog.bin.dir")?.takeIf { it.isNotBlank() } ?: return assumeTrue(false)
        val root = Path.of(directory)
        assumeTrue("const_v1.bin not found", Files.exists(root.resolve("const_v1.bin")))
        val provider = DirectoryAssetProvider(root)

        val boundaries = BinaryConstellationBoundaries.load(provider, path = "const_v1.bin")

        assertTrue(boundaries is BinaryConstellationBoundaries)
        val iau = boundaries.findByEq(Equatorial(100.0, -10.0))
        assertEquals("ORI", iau)
    }

    private data class RegionRecord(
        val iauCode: String,
        val minRaDeg: Double,
        val maxRaDeg: Double,
        val minDecDeg: Double,
        val maxDecDeg: Double,
    )

    private fun buildConstellationBinary(records: List<RegionRecord>): ByteArray {
        val payload = LittleEndianByteArray()
        records.forEach { region ->
            payload.writeString(region.iauCode)
            payload.writeDouble(region.minRaDeg)
            payload.writeDouble(region.maxRaDeg)
            payload.writeDouble(region.minDecDeg)
            payload.writeDouble(region.maxDecDeg)
        }
        val payloadBytes = payload.toByteArray()
        val crc = CRC32().apply { update(payloadBytes) }.value.toInt()
        val header = LittleEndianByteArray()
        header.writeAscii("PTSK")
        header.writeAscii("CONS")
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

        fun writeString(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            writeInt(bytes.size)
            buffer.addAll(bytes.toList())
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
