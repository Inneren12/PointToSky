package dev.pointtosky.core.catalog.binary

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * Parsed PTSKCAT0 real star catalog (HYG-derived; see
 * docs/star_catalog_ptskcat0_format.md). Pure JVM, no Android dependency —
 * asset loading is handled by a separate provider, as with [BinaryConstellationBoundaries].
 *
 * Records are sorted ascending by magnitude, so a "brightest N" prefix and a
 * mag-limit binary search boundary ([countBrighterOrEqual]) are both free.
 *
 * Format (little-endian):
 *   Header (28 bytes): magic="PTSKCAT0", version:i32, count:i32,
 *     magLimitCenti:i32, recSize:i32=16, epoch:i32=2000
 *   Records (count * 16 bytes): raDeg:f32, decDeg:f32, magCenti:i16,
 *     bvMilli:i16 (-32768 = unknown), hip:u32 (0 = none)
 *   Names (sparse): namesCount:i32, then { key:i32, len:u8, utf8[len] }
 *     key > 0 -> hip; key < 0 -> -(recordIndex + 1)
 */
class PtskCat0Catalog private constructor(
    val magLimit: Double,
    val epoch: Int,
    private val raDeg: FloatArray,
    private val decDeg: FloatArray,
    private val magCenti: ShortArray,
    private val bvMilli: ShortArray,
    private val hip: IntArray,
    private val namesByKey: Map<Int, String>,
) {
    val count: Int get() = raDeg.size

    fun raDegAt(index: Int): Float = raDeg[index]

    fun decDegAt(index: Int): Float = decDeg[index]

    fun magAt(index: Int): Double = magCenti[index] / 100.0

    /** Johnson B-V color index, or `null` if unknown (sentinel -32768). */
    fun bvAt(index: Int): Double? {
        val raw = bvMilli[index]
        return if (raw == BV_UNKNOWN) null else raw / 1000.0
    }

    /** Hipparcos number, or `0` if this star has none. */
    fun hipAt(index: Int): Int = hip[index]

    fun nameAt(index: Int): String? = namesByKey[nameKey(index)]

    /** Looks up a name by Hipparcos number. Returns `null` for `hipId <= 0`. */
    fun nameByHip(hipId: Int): String? {
        if (hipId <= 0) return null
        return namesByKey[hipId]
    }

    /**
     * Count of records with `mag <= limit` (records are sorted ascending by
     * mag, so this is also the boundary index of the "brighter than" prefix).
     */
    fun countBrighterOrEqual(limit: Double): Int {
        val limitCenti = Math.round(limit * 100.0).toInt()
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (magCenti[mid] <= limitCenti) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * Simple V1 spatial query for the matcher: linear scan with angular
     * separation. Returns record indices within [radiusDeg] of the query
     * point, optionally restricted to the mag-sorted brighter-than-limit
     * prefix. A real spatial index can replace this scan later without
     * changing the on-disk format.
     */
    fun nearby(raDegQuery: Double, decDegQuery: Double, radiusDeg: Double, magLimitQuery: Double? = null): List<Int> {
        if (radiusDeg <= 0.0) return emptyList()
        val searchLimit = magLimitQuery?.let { countBrighterOrEqual(it) } ?: count
        val result = ArrayList<Int>()
        for (i in 0 until searchLimit) {
            val sep = angularSeparationDeg(raDegQuery, decDegQuery, raDeg[i].toDouble(), decDeg[i].toDouble())
            if (sep <= radiusDeg) result += i
        }
        return result
    }

    private fun nameKey(index: Int): Int {
        val h = hip[index]
        return if (h > 0) h else -(index + 1)
    }

    companion object {
        const val MAGIC = "PTSKCAT0"
        const val VERSION = 1
        const val HEADER_SIZE = 28
        const val RECORD_SIZE = 16
        const val BV_UNKNOWN: Short = -32768

        /**
         * Parses a PTSKCAT0 binary from [bytes].
         *
         * @throws IllegalArgumentException if the magic, version, record size, or
         *   framing (truncated records/names) is invalid.
         */
        fun parse(bytes: ByteArray): PtskCat0Catalog {
            require(bytes.size >= HEADER_SIZE) { "Too short for PTSKCAT0 header: ${bytes.size} bytes" }

            val header = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val magicBytes = ByteArray(8)
            header.get(magicBytes)
            val magic = String(magicBytes, StandardCharsets.US_ASCII)
            require(magic == MAGIC) { "Bad magic: expected $MAGIC, got $magic" }

            val version = header.int
            require(version == VERSION) { "Unsupported PTSKCAT0 version: $version" }

            val count = header.int
            require(count >= 0) { "Negative count: $count" }

            val magLimitCenti = header.int
            val recSize = header.int
            require(recSize == RECORD_SIZE) { "Unexpected record size: $recSize, expected $RECORD_SIZE" }
            val epoch = header.int

            val recordsEnd = HEADER_SIZE + count * RECORD_SIZE
            require(bytes.size >= recordsEnd + Int.SIZE_BYTES) { "Truncated PTSKCAT0: missing records or names count" }

            val raDeg = FloatArray(count)
            val decDeg = FloatArray(count)
            val magCenti = ShortArray(count)
            val bvMilli = ShortArray(count)
            val hip = IntArray(count)

            val recordBuffer = ByteBuffer.wrap(bytes, HEADER_SIZE, count * RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until count) {
                raDeg[i] = recordBuffer.float
                decDeg[i] = recordBuffer.float
                magCenti[i] = recordBuffer.short
                bvMilli[i] = recordBuffer.short
                hip[i] = recordBuffer.int
            }

            var offset = recordsEnd
            val namesCount = ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN).int
            require(namesCount >= 0) { "Negative names count: $namesCount" }
            offset += Int.SIZE_BYTES

            val namesByKey = HashMap<Int, String>(namesCount)
            repeat(namesCount) {
                require(bytes.size >= offset + Int.SIZE_BYTES + 1) { "Truncated PTSKCAT0 names section" }
                val entry = ByteBuffer.wrap(bytes, offset, Int.SIZE_BYTES + 1).order(ByteOrder.LITTLE_ENDIAN)
                val key = entry.int
                val len = entry.get().toInt() and 0xFF
                offset += Int.SIZE_BYTES + 1
                require(bytes.size >= offset + len) { "Truncated PTSKCAT0 name string for key=$key" }
                namesByKey[key] = String(bytes, offset, len, StandardCharsets.UTF_8)
                offset += len
            }

            return PtskCat0Catalog(
                magLimit = magLimitCenti / 100.0,
                epoch = epoch,
                raDeg = raDeg,
                decDeg = decDeg,
                magCenti = magCenti,
                bvMilli = bvMilli,
                hip = hip,
                namesByKey = namesByKey,
            )
        }

        private fun angularSeparationDeg(ra1Deg: Double, dec1Deg: Double, ra2Deg: Double, dec2Deg: Double): Double {
            val ra1 = Math.toRadians(ra1Deg)
            val dec1 = Math.toRadians(dec1Deg)
            val ra2 = Math.toRadians(ra2Deg)
            val dec2 = Math.toRadians(dec2Deg)
            val cosSep = sin(dec1) * sin(dec2) + cos(dec1) * cos(dec2) * cos(ra1 - ra2)
            return Math.toDegrees(acos(cosSep.coerceIn(-1.0, 1.0)))
        }
    }
}
