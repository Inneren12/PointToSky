package dev.pointtosky.core.catalog.visibility.render

import dev.pointtosky.core.catalog.binary.PtskCat0Catalog
import dev.pointtosky.core.catalog.binary.RealStarCatalogProvider
import dev.pointtosky.core.catalog.visibility.RealStarVisibilityService
import dev.pointtosky.core.catalog.visibility.SkyQualityInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Pure JVM tests for [VisibleRealStarSnapshot] and [VisibleRealStarProvider],
 * composing a fake [RealStarCatalogProvider] over an in-memory PTSKCAT0
 * catalog, as in [dev.pointtosky.core.catalog.visibility.RealStarVisibilityServiceTest].
 * No renderer or PTSKCAT4 catalog-art code is touched by this class or its
 * tests.
 */
class VisibleRealStarSnapshotTest {

    private data class Rec(
        val raDeg: Float,
        val decDeg: Float,
        val mag: Double,
        val bv: Short,
        val hip: Int,
    )

    private fun buildCatalogBytes(
        records: List<Rec>,
        names: Map<Int, String> = emptyMap(),
        magLimit: Double = 8.0,
    ): ByteArray {
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
            recordBytes.putFloat(r.raDeg)
            recordBytes.putFloat(r.decDeg)
            recordBytes.putShort((r.mag * 100.0).roundToInt().toShort())
            recordBytes.putShort(r.bv)
            recordBytes.putInt(r.hip)
        }

        val namesBytes = ByteArrayOutputStream()
        namesBytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(names.size).array())
        names.forEach { (key, name) ->
            val nameUtf8 = name.toByteArray(Charsets.UTF_8)
            namesBytes.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(key).array())
            namesBytes.write(nameUtf8.size)
            namesBytes.write(nameUtf8)
        }

        return header + recordBytes.array() + namesBytes.toByteArray()
    }

    private val bvUnknown: Short = -32768

    private val sampleRecords = listOf(
        Rec(raDeg = 10.5f, decDeg = -20.25f, mag = -1.0, bv = 400, hip = 1),
        Rec(raDeg = 90.0f, decDeg = 0.0f, mag = 2.0, bv = bvUnknown, hip = 0),
        Rec(raDeg = 180.0f, decDeg = 45.0f, mag = 4.0, bv = -100, hip = 3),
        Rec(raDeg = 270.0f, decDeg = -45.0f, mag = 5.6, bv = 1200, hip = 4),
        Rec(raDeg = 359.9f, decDeg = 89.9f, mag = 6.5, bv = bvUnknown, hip = 5),
        Rec(raDeg = 0.0f, decDeg = -89.9f, mag = 8.0, bv = 300, hip = 6),
    )

    private fun fakeProvider(
        records: List<Rec> = sampleRecords,
        names: Map<Int, String> = mapOf(1 to "Sirius"),
    ): RealStarCatalogProvider {
        val catalog = PtskCat0Catalog.parse(buildCatalogBytes(records, names))
        return object : RealStarCatalogProvider {
            override fun load(): PtskCat0Catalog = catalog
        }
    }

    @Test
    fun `snapshot exposes limitingMagnitude and count from the source result`() {
        val service = RealStarVisibilityService(fakeProvider())

        val snapshot = VisibleRealStarSnapshot.from(service.select(SkyQualityInput.Bortle(5)))

        assertEquals(5.6, snapshot.limitingMagnitude, 0.0)
        assertEquals(4, snapshot.count)
    }

    @Test
    fun `starAt resolves ra dec mag bv hip and name from the backing catalog`() {
        val service = RealStarVisibilityService(fakeProvider())

        val snapshot = VisibleRealStarSnapshot.from(service.select(SkyQualityInput.LimitingMagnitude(4.0)))

        val brightest = snapshot.starAt(0)
        assertEquals(10.5, brightest.raDeg, 1e-4)
        assertEquals(-20.25, brightest.decDeg, 1e-4)
        assertEquals(-1.0, brightest.mag, 1e-9)
        assertEquals(0.4, brightest.bv!!, 1e-9)
        assertEquals(1, brightest.hip)
        assertEquals("Sirius", brightest.name)
    }

    @Test
    fun `starAt maps unknown bv to null`() {
        val service = RealStarVisibilityService(fakeProvider())

        val snapshot = VisibleRealStarSnapshot.from(service.select(SkyQualityInput.LimitingMagnitude(4.0)))

        assertNull(snapshot.starAt(1).bv)
    }

    @Test
    fun `starAt maps absent hip and name to null`() {
        val service = RealStarVisibilityService(fakeProvider())

        val snapshot = VisibleRealStarSnapshot.from(service.select(SkyQualityInput.LimitingMagnitude(4.0)))

        val noHip = snapshot.starAt(1)
        assertNull(noHip.hip)
        assertNull(noHip.name)
    }

    @Test
    fun `stars list matches starAt for every visible index in mag order`() {
        val service = RealStarVisibilityService(fakeProvider())

        val snapshot = VisibleRealStarSnapshot.from(service.select(SkyQualityInput.LimitingMagnitude(6.5)))

        assertEquals(5, snapshot.stars.size)
        for (i in 0 until snapshot.count) {
            assertEquals(snapshot.starAt(i), snapshot.stars[i])
        }
        // Mag-sorted ascending, so consecutive entries never decrease in brightness value.
        for (i in 1 until snapshot.stars.size) {
            assertTrue(snapshot.stars[i - 1].mag <= snapshot.stars[i].mag)
        }
    }

    @Test
    fun `starAt throws for an index outside 0 until count`() {
        val service = RealStarVisibilityService(fakeProvider())
        val snapshot = VisibleRealStarSnapshot.from(service.select(SkyQualityInput.LimitingMagnitude(4.0)))

        assertThrows(IndexOutOfBoundsException::class.java) { snapshot.starAt(-1) }
        assertThrows(IndexOutOfBoundsException::class.java) { snapshot.starAt(snapshot.count) }
    }

    @Test
    fun `empty selection yields an empty stars list and out-of-bounds starAt`() {
        val service = RealStarVisibilityService(fakeProvider())

        val snapshot = VisibleRealStarSnapshot.from(
            service.select(SkyQualityInput.LimitingMagnitude(Double.NEGATIVE_INFINITY)),
        )

        assertEquals(0, snapshot.count)
        assertTrue(snapshot.stars.isEmpty())
        assertThrows(IndexOutOfBoundsException::class.java) { snapshot.starAt(0) }
    }

    @Test
    fun `stars is a lazy view that does not eagerly resolve every entry`() {
        val service = RealStarVisibilityService(fakeProvider())

        val snapshot = VisibleRealStarSnapshot.from(service.select(SkyQualityInput.LimitingMagnitude(8.0)))
        val list = snapshot.stars

        // Constructing/holding the list must not throw or require touching every
        // index; only indexing in actually resolves an entry via starAt().
        assertEquals(sampleRecords.size, list.size)
        assertEquals(snapshot.starAt(2), list[2])
    }

    @Test
    fun `VisibleRealStarProvider composes select and adapt in one call`() {
        val service = RealStarVisibilityService(fakeProvider())

        val snapshot = VisibleRealStarProvider.snapshot(service, SkyQualityInput.Bortle(5))

        assertEquals(5.6, snapshot.limitingMagnitude, 0.0)
        assertEquals(4, snapshot.count)
        assertEquals(-1.0, snapshot.starAt(0).mag, 1e-9)
    }
}
