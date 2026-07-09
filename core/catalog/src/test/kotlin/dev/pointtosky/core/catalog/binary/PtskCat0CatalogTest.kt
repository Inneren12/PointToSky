package dev.pointtosky.core.catalog.binary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Pure JVM round-trip tests for [PtskCat0Catalog]. Bytes are built in-memory
 * (no asset file needed) mirroring the PTSKCAT0 writer in
 * :tools:catalog-packer, following the same pattern as
 * BinaryConstellationBoundariesLoadTest's TestBinaryBuilder.
 */
class PtskCat0CatalogTest {

    private data class Rec(
        val ra: Float,
        val dec: Float,
        val mag: Double,
        val bv: Double?,
        val hip: Int,
        val name: String? = null,
    )

    private fun build(
        records: List<Rec>,
        magLimit: Double = 8.0,
        magic: String = "PTSKCAT0",
        version: Int = 1,
        recSize: Int = 16,
        epoch: Int = 2000,
    ): ByteArray {
        val header = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(magic.toByteArray(Charsets.US_ASCII))
            putInt(version)
            putInt(records.size)
            putInt((magLimit * 100.0).roundToInt())
            putInt(recSize)
            putInt(epoch)
        }.array()

        val recordBytes = ByteBuffer.allocate(records.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        val names = ArrayList<Pair<Int, String>>()
        records.forEachIndexed { index, r ->
            recordBytes.putFloat(r.ra)
            recordBytes.putFloat(r.dec)
            recordBytes.putShort((r.mag * 100.0).roundToInt().toShort())
            val bvMilli = r.bv?.let { (it * 1000.0).roundToInt() } ?: -32768
            recordBytes.putShort(bvMilli.toShort())
            recordBytes.putInt(r.hip)
            r.name?.let { names += (if (r.hip > 0) r.hip else -(index + 1)) to it }
        }

        val namesBytes = ByteArrayOutputStream()
        namesBytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(names.size).array())
        for ((key, name) in names) {
            val utf8 = name.toByteArray(Charsets.UTF_8)
            namesBytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(key).array())
            namesBytes.write(utf8.size)
            namesBytes.write(utf8)
        }

        return header + recordBytes.array() + namesBytes.toByteArray()
    }

    @Test
    fun `round trips header and records`() {
        val records = listOf(
            Rec(101.2875f, -16.7161f, -1.46, null, 32349, "Sirius"),
            Rec(95.9879f, -52.6957f, -0.74, 0.15, 0, "Canopus"),
        )
        val catalog = PtskCat0Catalog.parse(build(records, magLimit = 6.5))

        assertEquals(2, catalog.count)
        assertEquals(6.5, catalog.magLimit, 1e-9)
        assertEquals(2000, catalog.epoch)
        assertEquals(101.2875f, catalog.raDegAt(0), 1e-3f)
        assertEquals(-16.7161f, catalog.decDegAt(0), 1e-3f)
        assertEquals(-1.46, catalog.magAt(0), 1e-6)
        assertEquals(32349, catalog.hipAt(0))
        assertEquals("Sirius", catalog.nameAt(0))
        assertEquals("Sirius", catalog.nameByHip(32349))
        assertEquals(0.15, catalog.bvAt(1)!!, 1e-6)
    }

    @Test
    fun `records are sorted ascending by magnitude`() {
        val records = listOf(
            Rec(0f, 0f, -1.0, null, 1),
            Rec(1f, 0f, 2.0, null, 2),
            Rec(2f, 0f, 5.0, null, 3),
            Rec(3f, 0f, 5.0, null, 4),
        )
        val catalog = PtskCat0Catalog.parse(build(records))
        for (i in 1 until catalog.count) {
            assertTrue(catalog.magAt(i - 1) <= catalog.magAt(i))
        }
    }

    @Test
    fun `bv sentinel means color unknown`() {
        val catalog = PtskCat0Catalog.parse(build(listOf(Rec(0f, 0f, 3.0, null, 5))))
        assertNull(catalog.bvAt(0))
    }

    @Test
    fun `hip zero means no HIP and hip lookup returns null`() {
        val catalog = PtskCat0Catalog.parse(build(listOf(Rec(0f, 0f, 3.0, null, 0, "NoHip"))))
        assertEquals(0, catalog.hipAt(0))
        assertNull(catalog.nameByHip(0))
        assertEquals("NoHip", catalog.nameAt(0))
    }

    @Test
    fun `binary search finds correct mag boundary`() {
        val records = listOf(
            Rec(0f, 0f, -1.0, null, 1),
            Rec(0f, 0f, 2.0, null, 2),
            Rec(0f, 0f, 4.0, null, 3),
            Rec(0f, 0f, 6.5, null, 4),
            Rec(0f, 0f, 8.0, null, 5),
        )
        val catalog = PtskCat0Catalog.parse(build(records, magLimit = 8.0))

        assertEquals(4, catalog.countBrighterOrEqual(6.5))
        assertEquals(5, catalog.countBrighterOrEqual(8.0))
        assertEquals(0, catalog.countBrighterOrEqual(-2.0))
        val boundary = catalog.countBrighterOrEqual(6.5)
        for (i in 0 until boundary) {
            assertTrue(catalog.magAt(i) <= 6.5)
        }
        for (i in boundary until catalog.count) {
            assertTrue(catalog.magAt(i) > 6.5)
        }
    }

    @Test
    fun `name lookup by hip and by negative record index key`() {
        val records = listOf(
            Rec(0f, 0f, 1.0, null, 100, "HasHip"),
            Rec(0f, 0f, 2.0, null, 0, "NoHipButNamed"),
        )
        val catalog = PtskCat0Catalog.parse(build(records))

        assertEquals("HasHip", catalog.nameByHip(100))
        assertEquals("HasHip", catalog.nameAt(0))
        assertEquals("NoHipButNamed", catalog.nameAt(1))
        assertNull(catalog.nameByHip(0))
    }

    @Test
    fun `nearby returns records within radius respecting mag limit`() {
        val records = listOf(
            Rec(10f, 0f, 1.0, null, 1),   // close, bright
            Rec(10.5f, 0f, 7.0, null, 2), // close, faint
            Rec(90f, 0f, 1.0, null, 3),   // far, bright
        )
        val catalog = PtskCat0Catalog.parse(build(records, magLimit = 8.0))

        val all = catalog.nearby(10.0, 0.0, radiusDeg = 2.0)
        assertEquals(setOf(0, 1), all.toSet())

        val brightOnly = catalog.nearby(10.0, 0.0, radiusDeg = 2.0, magLimitQuery = 3.0)
        assertEquals(setOf(0), brightOnly.toSet())
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects wrong magic`() {
        PtskCat0Catalog.parse(build(listOf(Rec(0f, 0f, 1.0, null, 1)), magic = "WRONGMAG"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects unsupported version`() {
        PtskCat0Catalog.parse(build(listOf(Rec(0f, 0f, 1.0, null, 1)), version = 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects wrong record size`() {
        PtskCat0Catalog.parse(build(listOf(Rec(0f, 0f, 1.0, null, 1)), recSize = 32))
    }
}
