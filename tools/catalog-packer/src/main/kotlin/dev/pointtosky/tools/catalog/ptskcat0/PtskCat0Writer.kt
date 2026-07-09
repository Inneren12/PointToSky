package dev.pointtosky.tools.catalog.ptskcat0

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * One HYG star row after CSV parsing, before mag-limit filtering / sorting.
 */
data class PtskCat0StarInput(
    val id: Int,
    val raDeg: Double,
    val decDeg: Double,
    val mag: Double,
    val bv: Double?,
    val hip: Int,
    val name: String?,
)

data class PtskCat0PackResult(val bytes: ByteArray, val count: Int)

/**
 * Packs [PtskCat0StarInput] rows into the PTSKCAT0 binary format
 * (see docs/star_catalog_ptskcat0_format.md). Records are filtered to
 * `mag <= magLimit` and sorted ascending by (mag, hip, id) so that a
 * "brightest N" prefix and a mag binary search are both free at read time.
 */
object PtskCat0Writer {
    const val MAGIC = "PTSKCAT0"
    const val VERSION = 1
    const val HEADER_SIZE = 28
    const val RECORD_SIZE = 16
    const val EPOCH = 2000
    const val BV_UNKNOWN: Int = -32768
    private const val MAX_NAME_UTF8_BYTES = 255

    fun write(stars: List<PtskCat0StarInput>, magLimit: Double): PtskCat0PackResult {
        val filtered = stars
            .filter { it.mag <= magLimit }
            .sortedWith(compareBy({ it.mag }, { it.hip }, { it.id }))

        val records = ByteArrayOutputStream(filtered.size * RECORD_SIZE)
        val names = ArrayList<Pair<Int, String>>()
        val recordBuffer = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN)

        filtered.forEachIndexed { index, star ->
            recordBuffer.clear()
            recordBuffer.putFloat(star.raDeg.toFloat())
            recordBuffer.putFloat(star.decDeg.toFloat())
            recordBuffer.putShort((star.mag * 100.0).roundToInt().toShort())
            val bvCenti = star.bv?.let { (it * 1000.0).roundToInt() } ?: BV_UNKNOWN
            recordBuffer.putShort(bvCenti.toShort())
            recordBuffer.putInt(star.hip)
            records.write(recordBuffer.array())

            val name = star.name?.trim()?.takeIf { it.isNotEmpty() }
            if (name != null) {
                val key = if (star.hip > 0) star.hip else -(index + 1)
                names += key to name
            }
        }

        val namesBytes = ByteArrayOutputStream()
        namesBytes.write(intLe(names.size))
        for ((key, name) in names) {
            val utf8 = name.toByteArray(Charsets.UTF_8)
            require(utf8.size <= MAX_NAME_UTF8_BYTES) {
                "Star name too long for PTSKCAT0 (max $MAX_NAME_UTF8_BYTES UTF-8 bytes): $name"
            }
            namesBytes.write(intLe(key))
            namesBytes.write(utf8.size)
            namesBytes.write(utf8)
        }

        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(MAGIC.toByteArray(Charsets.US_ASCII))
            putInt(VERSION)
            putInt(filtered.size)
            putInt((magLimit * 100.0).roundToInt())
            putInt(RECORD_SIZE)
            putInt(EPOCH)
        }.array()

        val out = ByteArrayOutputStream(HEADER_SIZE + records.size() + namesBytes.size())
        out.write(header)
        out.write(records.toByteArray())
        out.write(namesBytes.toByteArray())
        return PtskCat0PackResult(out.toByteArray(), filtered.size)
    }

    private fun intLe(value: Int): ByteArray =
        ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
}
