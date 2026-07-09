package dev.pointtosky.core.catalog.visibility

import dev.pointtosky.core.astro.visibility.darkNelmFromSqm
import dev.pointtosky.core.catalog.binary.PtskCat0Catalog
import dev.pointtosky.core.catalog.binary.RealStarCatalogLoadException
import dev.pointtosky.core.catalog.binary.RealStarCatalogProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * Pure JVM tests for [RealStarVisibilityService], composing a fake
 * [RealStarCatalogProvider] over an in-memory PTSKCAT0 catalog with
 * [LimitingMagnitudeModel] and [dev.pointtosky.core.catalog.binary.RealStarVisibilityFilter].
 */
class RealStarVisibilityServiceTest {

    private data class Rec(val mag: Double, val hip: Int)

    private fun buildCatalogBytes(records: List<Rec>, magLimit: Double = 8.0): ByteArray {
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

    private val sampleRecords = listOf(
        Rec(-1.0, 1),
        Rec(2.0, 2),
        Rec(4.0, 3),
        Rec(5.6, 4),
        Rec(6.5, 5),
        Rec(8.0, 6),
    )

    private fun fakeProvider(records: List<Rec> = sampleRecords): RealStarCatalogProvider {
        val catalog = PtskCat0Catalog.parse(buildCatalogBytes(records))
        return object : RealStarCatalogProvider {
            override fun load(): PtskCat0Catalog = catalog
        }
    }

    @Test
    fun `Bortle input produces expected limiting magnitude and selection count`() {
        val provider = fakeProvider()
        val service = RealStarVisibilityService(provider)

        val result = service.select(SkyQualityInput.Bortle(5))

        assertEquals(5.6, result.limitingMagnitude, 0.0)
        assertEquals(LimitingMagnitudeModel.fromBortle(5), result.limitingMagnitude, 0.0)
        assertEquals(4, result.selection.count)
        for (i in result.selection.indices) {
            assertTrue(result.catalog.magAt(i) <= result.limitingMagnitude)
        }
    }

    @Test
    fun `Sqm input delegates through LimitingMagnitudeModel fromSqm`() {
        val expected = darkNelmFromSqm(17.5)
        val provider = fakeProvider(
            listOf(
                Rec(-1.0, 1),
                Rec(2.0, 2),
                Rec(4.0, 3),
                Rec(expected, 4),
                Rec(6.5, 5),
                Rec(8.0, 6),
            ),
        )
        val service = RealStarVisibilityService(provider)

        val result = service.select(SkyQualityInput.Sqm(17.5))

        assertEquals(LimitingMagnitudeModel.fromSqm(17.5), result.limitingMagnitude, 1e-9)
        assertEquals(expected, result.limitingMagnitude, 1e-9)
        assertEquals(4, result.selection.count)
    }

    @Test
    fun `direct limiting magnitude is used as-is`() {
        val provider = fakeProvider()
        val service = RealStarVisibilityService(provider)

        val result = service.select(SkyQualityInput.LimitingMagnitude(4.0))

        assertEquals(4.0, result.limitingMagnitude, 0.0)
        assertEquals(3, result.selection.count)
    }

    @Test
    fun `direct limiting magnitude above the supported range is clamped`() {
        val provider = fakeProvider()
        val service = RealStarVisibilityService(provider)

        val result = service.select(SkyQualityInput.LimitingMagnitude(100.0))

        // Clamped to LimitingMagnitudeModel.SUPPORTED_RANGE (0.0..8.0): 100.0 is
        // finite, so it is coerced down to the upper bound, 8.0, before reaching
        // RealStarVisibilityFilter.
        assertEquals(8.0, result.limitingMagnitude, 0.0)
        assertTrue(100.0 !in LimitingMagnitudeModel.SUPPORTED_RANGE)
        assertEquals(sampleRecords.size, result.selection.count)
    }

    @Test
    fun `direct limiting magnitude below the supported range is clamped`() {
        val provider = fakeProvider()
        val service = RealStarVisibilityService(provider)

        val result = service.select(SkyQualityInput.LimitingMagnitude(-5.0))

        // Clamped up to the lower bound, 0.0. Only the sample record brighter than
        // (i.e. with a lower magnitude than) 0.0 is selected.
        assertEquals(0.0, result.limitingMagnitude, 0.0)
        assertEquals(1, result.selection.count)
    }

    @Test
    fun `direct positive infinity selects every record`() {
        val provider = fakeProvider()
        val service = RealStarVisibilityService(provider)

        val result = service.select(SkyQualityInput.LimitingMagnitude(Double.POSITIVE_INFINITY))

        assertEquals(Double.POSITIVE_INFINITY, result.limitingMagnitude, 0.0)
        assertEquals(sampleRecords.size, result.selection.count)
    }

    @Test
    fun `direct negative infinity selects no records`() {
        val provider = fakeProvider()
        val service = RealStarVisibilityService(provider)

        val result = service.select(SkyQualityInput.LimitingMagnitude(Double.NEGATIVE_INFINITY))

        assertEquals(Double.NEGATIVE_INFINITY, result.limitingMagnitude, 0.0)
        assertEquals(0, result.selection.count)
        assertTrue(result.selection.isEmpty)
    }

    @Test
    fun `invalid Bortle class propagates IllegalArgumentException`() {
        val service = RealStarVisibilityService(fakeProvider())

        assertThrows(IllegalArgumentException::class.java) {
            service.select(SkyQualityInput.Bortle(0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            service.select(SkyQualityInput.Bortle(10))
        }
    }

    @Test
    fun `NaN direct limiting magnitude fails fast`() {
        val service = RealStarVisibilityService(fakeProvider())

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.select(SkyQualityInput.LimitingMagnitude(Double.NaN))
        }
        assertTrue(error.message.orEmpty().contains("NaN"))
    }

    @Test
    fun `provider load exception propagates as RealStarCatalogLoadException`() {
        val failingProvider = object : RealStarCatalogProvider {
            override fun load(): PtskCat0Catalog = throw RealStarCatalogLoadException("boom")
        }
        val service = RealStarVisibilityService(failingProvider)

        assertThrows(RealStarCatalogLoadException::class.java) {
            service.select(SkyQualityInput.Bortle(5))
        }
    }

    @Test
    fun `invalid input fails before the catalog provider is invoked`() {
        var loadCalled = false
        val provider = object : RealStarCatalogProvider {
            override fun load(): PtskCat0Catalog {
                loadCalled = true
                return PtskCat0Catalog.parse(buildCatalogBytes(sampleRecords))
            }
        }
        val service = RealStarVisibilityService(provider)

        assertThrows(IllegalArgumentException::class.java) {
            service.select(SkyQualityInput.Bortle(0))
        }
        assertTrue("provider.load() should not run for invalid input", !loadCalled)
    }

    @Test
    fun `provider is called exactly once per select call`() {
        val catalog = PtskCat0Catalog.parse(buildCatalogBytes(sampleRecords))
        var loadCount = 0
        val provider = object : RealStarCatalogProvider {
            override fun load(): PtskCat0Catalog {
                loadCount++
                return catalog
            }
        }
        val service = RealStarVisibilityService(provider)

        service.select(SkyQualityInput.Bortle(5))
        assertEquals(1, loadCount)

        service.select(SkyQualityInput.LimitingMagnitude(4.0))
        assertEquals(2, loadCount)
    }

    @Test
    fun `no star data is copied, result exposes the provider's catalog instance`() {
        val catalog = PtskCat0Catalog.parse(buildCatalogBytes(sampleRecords))
        val provider = object : RealStarCatalogProvider {
            override fun load(): PtskCat0Catalog = catalog
        }
        val service = RealStarVisibilityService(provider)

        val result = service.select(SkyQualityInput.LimitingMagnitude(4.0))

        assertSame(catalog, result.catalog)
    }
}
