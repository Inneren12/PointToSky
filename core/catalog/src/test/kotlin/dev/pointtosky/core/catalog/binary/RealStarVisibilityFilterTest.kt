package dev.pointtosky.core.catalog.binary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Pure JVM tests for [RealStarVisibilityFilter] over an in-memory PTSKCAT0
 * catalog, mirroring the builder in [PtskCat0CatalogTest].
 */
class RealStarVisibilityFilterTest {

    private data class Rec(val mag: Double, val hip: Int)

    private fun build(records: List<Rec>, magLimit: Double = 8.0): ByteArray {
        val header = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("PTSKCAT0".toByteArray(Charsets.US_ASCII))
            putInt(1)
            putInt(records.size)
            putInt((magLimit * 100.0).roundToInt())
            putInt(16)
            putInt(2000)
        }.array()

        val recordBytes = ByteBuffer.allocate(records.size * 16).order(ByteOrder.LITTLE_ENDIAN)
        records.forEach { r ->
            recordBytes.putFloat(0f)
            recordBytes.putFloat(0f)
            recordBytes.putShort((r.mag * 100.0).roundToInt().toShort())
            recordBytes.putShort(-32768) // bv unknown
            recordBytes.putInt(r.hip)
        }

        val namesBytes = ByteArrayOutputStream()
        namesBytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0).array())

        return header + recordBytes.array() + namesBytes.toByteArray()
    }

    private fun catalog(records: List<Rec>, magLimit: Double = 8.0) =
        PtskCat0Catalog.parse(build(records, magLimit))

    private val sample = listOf(
        Rec(-1.0, 1),
        Rec(2.0, 2),
        Rec(4.0, 3),
        Rec(6.5, 4),
        Rec(6.5, 5),
        Rec(8.0, 6),
    )

    @Test
    fun `exact mag boundary includes stars equal to the limit`() {
        val cat = catalog(sample)

        // 6.5 appears twice (indices 3 and 4); both must be included.
        val selection = RealStarVisibilityFilter.select(cat, 6.5)

        assertEquals(5, selection.count)
        for (i in selection.indices) {
            assertTrue(cat.magAt(i) <= 6.5)
        }
        assertTrue(cat.magAt(selection.count) > 6.5)
    }

    @Test
    fun `too bright limit returns empty selection`() {
        val cat = catalog(sample)

        val selection = RealStarVisibilityFilter.select(cat, -5.0)

        assertEquals(0, selection.count)
        assertTrue(selection.isEmpty)
        assertEquals(0 until 0, selection.indices)
    }

    @Test
    fun `limit above catalog max returns all records`() {
        val cat = catalog(sample)

        val selection = RealStarVisibilityFilter.select(cat, 100.0)

        assertEquals(cat.count, selection.count)
        assertEquals(0 until cat.count, selection.indices)
    }

    @Test
    fun `positive infinity selects every record`() {
        val cat = catalog(sample)

        val selection = RealStarVisibilityFilter.select(cat, Double.POSITIVE_INFINITY)

        assertEquals(cat.count, selection.count)
    }

    @Test
    fun `negative infinity selects no records`() {
        val cat = catalog(sample)

        val selection = RealStarVisibilityFilter.select(cat, Double.NEGATIVE_INFINITY)

        assertEquals(0, selection.count)
        assertTrue(selection.isEmpty)
    }

    @Test
    fun `NaN limit fails fast`() {
        val cat = catalog(sample)

        assertThrows(IllegalArgumentException::class.java) {
            RealStarVisibilityFilter.select(cat, Double.NaN)
        }
    }

    @Test
    fun `empty catalog yields empty selection regardless of limit`() {
        val cat = catalog(emptyList())

        assertEquals(0, RealStarVisibilityFilter.select(cat, 10.0).count)
        assertEquals(0, RealStarVisibilityFilter.select(cat, Double.POSITIVE_INFINITY).count)
    }
}
