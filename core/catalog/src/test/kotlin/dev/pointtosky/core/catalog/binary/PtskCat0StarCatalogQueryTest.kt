package dev.pointtosky.core.catalog.binary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.test.assertFailsWith

/**
 * Pure JVM round-trip tests for [PtskCat0StarCatalogQuery]: the adapter must hand `:core:astro-core`
 * exactly the stars [PtskCat0Catalog.nearby] found, with the identity, direction and magnitude the
 * catalog holds and no others.
 *
 * Bytes are built in-memory, mirroring the builder in `PtskCat0CatalogTest` (records must be sorted
 * ascending by magnitude — the format's own invariant, which `countBrighterOrEqual`'s binary search
 * depends on), so no packed asset is needed to run this.
 */
class PtskCat0StarCatalogQueryTest {
    private val catalog =
        PtskCat0Catalog.parse(
            build(
                listOf(
                    Rec(ra = 10.0f, dec = 20.0f, mag = 1.0, hip = 101),
                    Rec(ra = 11.0f, dec = 20.0f, mag = 2.0, hip = 102),
                    Rec(ra = 10.0f, dec = 25.0f, mag = 3.0, hip = 103),
                    Rec(ra = 200.0f, dec = -40.0f, mag = 4.0, hip = 104),
                ),
            ),
        )
    private val query = PtskCat0StarCatalogQuery(catalog)

    private val queryRaRad = Math.toRadians(10.0)
    private val queryDecRad = Math.toRadians(20.0)

    @Test
    fun `returns the same records the catalog itself finds`() {
        val radiusRad = Math.toRadians(6.0)

        val found = query.nearby(queryRaRad, queryDecRad, radiusRad)

        assertEquals(
            catalog.nearby(raDegQuery = 10.0, decDegQuery = 20.0, radiusDeg = 6.0),
            found.map { it.catalogIndex },
        )
        assertEquals(listOf(0, 1, 2), found.map { it.catalogIndex })
    }

    @Test
    fun `each returned star carries the catalog's own direction and magnitude, in radians`() {
        val found = query.nearby(queryRaRad, queryDecRad, Math.toRadians(180.0))

        assertEquals(catalog.count, found.size)
        for (star in found) {
            val index = star.catalogIndex
            assertEquals(catalog.raDegAt(index).toDouble(), Math.toDegrees(star.rightAscensionRad), 1e-9)
            assertEquals(catalog.decDegAt(index).toDouble(), Math.toDegrees(star.declinationRad), 1e-9)
            assertEquals(catalog.magAt(index), star.magnitude!!, 1e-9)
        }
    }

    @Test
    fun `a tight cone excludes the star just outside it`() {
        // Record 1 sits 1 deg of RA away at dec 20, i.e. ~0.94 deg of great-circle separation.
        val inside = query.nearby(queryRaRad, queryDecRad, Math.toRadians(1.0))
        val outside = query.nearby(queryRaRad, queryDecRad, Math.toRadians(0.5))

        assertEquals(listOf(0, 1), inside.map { it.catalogIndex })
        assertEquals(listOf(0), outside.map { it.catalogIndex })
    }

    @Test
    fun `the magnitude limit is a brighter-or-equal cut over the cone`() {
        val radiusRad = Math.toRadians(6.0)

        fun indicesUpTo(magnitudeLimit: Double?) =
            query.nearby(queryRaRad, queryDecRad, radiusRad, magnitudeLimit).map { it.catalogIndex }

        assertEquals(listOf(0), indicesUpTo(1.5))
        assertEquals(listOf(0, 1), indicesUpTo(2.0))
        assertEquals(listOf(0, 1, 2), indicesUpTo(null))
    }

    @Test
    fun `a zero radius returns nothing even when a star sits exactly on the query point`() {
        assertEquals(emptyList<Int>(), query.nearby(queryRaRad, queryDecRad, radiusRad = 0.0).map { it.catalogIndex })
    }

    @Test
    fun `an unwrapped right ascension names the same direction`() {
        val radiusRad = Math.toRadians(6.0)

        val wrapped = query.nearby(queryRaRad, queryDecRad, radiusRad)
        val unwrapped = query.nearby(queryRaRad + 2.0 * PI, queryDecRad, radiusRad)

        assertEquals(wrapped, unwrapped)
    }

    @Test
    fun `identity is the record index, so the caller can read the rest of the record itself`() {
        val found = query.nearby(queryRaRad, queryDecRad, Math.toRadians(1.0))

        // The adapter deliberately carries no Hipparcos number, name or colour; the catalog handle the
        // caller already owns is where those come from, keyed by the index the adapter did carry.
        assertEquals(listOf(101, 102), found.map { catalog.hipAt(it.catalogIndex) })
    }

    @Test
    fun `a malformed query throws instead of reading as an empty sky`() {
        val radiusRad = Math.toRadians(6.0)

        assertFailsWith<IllegalArgumentException> { query.nearby(Double.NaN, queryDecRad, radiusRad) }
        assertFailsWith<IllegalArgumentException> { query.nearby(queryRaRad, PI, radiusRad) }
        assertFailsWith<IllegalArgumentException> { query.nearby(queryRaRad, queryDecRad, -1e-9) }
        assertFailsWith<IllegalArgumentException> { query.nearby(queryRaRad, queryDecRad, Double.POSITIVE_INFINITY) }
    }

    @Test
    fun `the magnitude cut is exact Double arithmetic, not the catalog's centi-magnitude rounding`() {
        // Record 1 is stored as magnitude 2.00. PtskCat0Catalog converts a queried limit with
        // round(limit * 100), so 1.995 rounds to 200 and its own prefix would admit that star — a star
        // strictly fainter than asked for. The port promises star.magnitude <= magnitudeLimit against
        // the Double the caller passed, so the storage quantization must not leak through.
        val radiusRad = Math.toRadians(6.0)

        fun indicesUpTo(magnitudeLimit: Double) =
            query.nearby(queryRaRad, queryDecRad, radiusRad, magnitudeLimit).map { it.catalogIndex }

        assertEquals("1.994 must exclude a stored 2.00", listOf(0), indicesUpTo(1.994))
        assertEquals("1.995 must exclude a stored 2.00", listOf(0), indicesUpTo(1.995))
        assertEquals("1.999 must exclude a stored 2.00", listOf(0), indicesUpTo(1.999))
        assertEquals("2.000 must include a stored 2.00", listOf(0, 1), indicesUpTo(2.000))
        assertEquals("2.001 must include a stored 2.00", listOf(0, 1), indicesUpTo(2.001))

        // The bug is real in the layer underneath, not hypothetical: the raw reader does admit it.
        assertTrue(
            "the underlying prefix is expected to be over-inclusive at this boundary",
            catalog.nearby(raDegQuery = 10.0, decDegQuery = 20.0, radiusDeg = 6.0, magLimitQuery = 1.995).contains(1),
        )
    }

    @Test
    fun `every returned star satisfies the exact limit`() {
        val radiusRad = Math.toRadians(180.0)

        for (limit in listOf(-2.0, 0.999, 1.0, 1.004, 2.5, 3.999, 4.0, 100.0)) {
            val found = query.nearby(queryRaRad, queryDecRad, radiusRad, magnitudeLimit = limit)

            assertTrue(
                "a returned star was fainter than the limit $limit",
                found.all { it.magnitude!! <= limit },
            )
            // ...and nothing that qualified was dropped: the exact set is what the catalog itself holds.
            val expected = (0 until catalog.count).filter { catalog.magAt(it) <= limit }
            assertEquals("limit $limit", expected, found.map { it.catalogIndex })
        }
    }

    @Test
    fun `a limit beyond the centi-magnitude range still returns the exact set`() {
        // Math.round(1e10 * 100.0).toInt() overflows to a wrapped, possibly negative value, which would
        // empty the reader's prefix and drop every star before the exact cut ever ran. Such limits skip
        // the prefix entirely and fall through to the post-filter.
        val radiusRad = Math.toRadians(180.0)

        val huge = query.nearby(queryRaRad, queryDecRad, radiusRad, magnitudeLimit = 1e10)
        assertEquals(listOf(0, 1, 2, 3), huge.map { it.catalogIndex })

        val hugelyNegative = query.nearby(queryRaRad, queryDecRad, radiusRad, magnitudeLimit = -1e10)
        assertEquals(emptyList<Int>(), hugelyNegative.map { it.catalogIndex })
    }

    @Test
    fun `an infinite magnitude limit is rejected rather than silently selecting nothing`() {
        val radiusRad = Math.toRadians(6.0)

        // Math.round(+inf * 100.0).toInt() is -1, so countBrighterOrEqual would return 0 and an
        // "unlimited" query would come back empty. The port's validator is what stops that reaching here.
        assertFailsWith<IllegalArgumentException> {
            query.nearby(queryRaRad, queryDecRad, radiusRad, magnitudeLimit = Double.POSITIVE_INFINITY)
        }
        assertTrue(
            "the reader's own centi-magnitude conversion is why the limit must be finite",
            catalog.countBrighterOrEqual(Double.POSITIVE_INFINITY) == 0,
        )
    }

    private data class Rec(
        val ra: Float,
        val dec: Float,
        val mag: Double,
        val bv: Double? = null,
        val hip: Int = 0,
        val name: String? = null,
    )

    private fun build(
        records: List<Rec>,
        magLimit: Double = 8.0,
    ): ByteArray {
        val header =
            ByteBuffer
                .allocate(PtskCat0Catalog.HEADER_SIZE)
                .order(ByteOrder.LITTLE_ENDIAN)
                .apply {
                    put(PtskCat0Catalog.MAGIC.toByteArray(Charsets.US_ASCII))
                    putInt(PtskCat0Catalog.VERSION)
                    putInt(records.size)
                    putInt((magLimit * 100.0).roundToInt())
                    putInt(PtskCat0Catalog.RECORD_SIZE)
                    putInt(2000)
                }.array()

        val recordBytes =
            ByteBuffer.allocate(records.size * PtskCat0Catalog.RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val names = ArrayList<Pair<Int, String>>()
        records.forEachIndexed { index, record ->
            recordBytes.putFloat(record.ra)
            recordBytes.putFloat(record.dec)
            recordBytes.putShort((record.mag * 100.0).roundToInt().toShort())
            recordBytes.putShort(record.bv?.let { (it * 1000.0).roundToInt().toShort() } ?: PtskCat0Catalog.BV_UNKNOWN)
            recordBytes.putInt(record.hip)
            record.name?.let { names += (if (record.hip > 0) record.hip else -(index + 1)) to it }
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
}
