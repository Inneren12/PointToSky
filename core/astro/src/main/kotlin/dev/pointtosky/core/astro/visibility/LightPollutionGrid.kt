package dev.pointtosky.core.astro.visibility

import java.util.zip.Inflater
import kotlin.math.floor

/**
 * Parsed PTSKLP01 light-pollution grid.  Maps a geographic coordinate to a continuous [SkyBrightness].
 *
 * Obtain via [parse]; lookup via [sqmAt].  Pure JVM — no Android dependencies.
 * Asset loading is handled by a separate provider (5a-ii-C).
 *
 * Format v3 (little-endian, equirectangular):
 *   off  type     field
 *    0   8s       magic = "PTSKLP01"
 *    8   int32    version = 3
 *   12   int32    rows
 *   16   int32    cols
 *   20   float64  latTopDeg
 *   28   float64  lonLeftDeg
 *   36   float64  degPerCell
 *   44   int32    flags  (bit 0 = placeholder / synthetic data)
 *   48   int32    compLen
 *   52   compLen  zlib payload
 *
 * Payload cell semantics: `0` = nodata (ocean); `1..255` = SQM via
 * `sqm_mag = SQM_MIN + (byte − 1) * SQM_STEP`.
 */
class LightPollutionGrid private constructor(
    private val rows: Int,
    private val cols: Int,
    private val latTopDeg: Double,
    private val lonLeftDeg: Double,
    private val degPerCell: Double,
    private val cells: ByteArray,
    val isPlaceholder: Boolean,
) {
    /**
     * Return the [SkyBrightness] at [latDeg]/[lonDeg], or `null` for nodata (ocean) cells
     * and coordinates outside this grid's extent.  Longitude is normalised modulo 360°.
     */
    fun sqmAt(latDeg: Double, lonDeg: Double): SkyBrightness? {
        // Normalise lon so the offset from lonLeftDeg is in [0, cols*degPerCell).
        val lonOffset = ((lonDeg - lonLeftDeg) % 360.0 + 360.0) % 360.0
        val col = floor(lonOffset / degPerCell).toInt()
        val row = floor((latTopDeg - latDeg) / degPerCell).toInt()
        if (row !in 0 until rows || col !in 0 until cols) return null
        val b = cells[row * cols + col].toInt() and 0xFF
        return if (b == 0) null else SkyBrightness(SQM_MIN + (b - 1) * SQM_STEP)
    }

    companion object {
        private val MAGIC = "PTSKLP01".toByteArray(Charsets.US_ASCII)
        private const val EXPECTED_VERSION = 3
        private const val HEADER_SIZE = 52
        const val SQM_MIN = 16.0
        const val SQM_STEP = 0.1

        /**
         * Parse a PTSKLP01 binary from [bytes].
         *
         * @throws IllegalArgumentException if the magic, version, or inflated length is wrong.
         */
        fun parse(bytes: ByteArray): LightPollutionGrid {
            require(bytes.size >= HEADER_SIZE) {
                "Too short for PTSKLP01 header: ${bytes.size} bytes"
            }
            for (i in MAGIC.indices) {
                require(bytes[i] == MAGIC[i]) {
                    "Bad magic at byte $i: expected 0x${MAGIC[i].toInt().and(0xFF).toString(16)}, " +
                        "got 0x${bytes[i].toInt().and(0xFF).toString(16)}"
                }
            }
            val version = bytes.leInt(8)
            require(version == EXPECTED_VERSION) { "Unsupported PTSKLP01 version: $version" }

            val rows = bytes.leInt(12)
            val cols = bytes.leInt(16)
            val latTop = bytes.leDouble(20)
            val lonLeft = bytes.leDouble(28)
            val degPerCell = bytes.leDouble(36)
            val flags = bytes.leInt(44)
            val compLen = bytes.leInt(48)

            require(bytes.size >= HEADER_SIZE + compLen) {
                "File truncated: expected at least ${HEADER_SIZE + compLen} bytes, got ${bytes.size}"
            }

            val expected = rows * cols
            val inflated = ByteArray(expected)
            val inflater = Inflater()
            try {
                inflater.setInput(bytes, HEADER_SIZE, compLen)
                val actual = inflater.inflate(inflated)
                require(actual == expected) {
                    "Inflated payload length $actual != rows*cols $expected"
                }
            } finally {
                inflater.end()
            }

            return LightPollutionGrid(rows, cols, latTop, lonLeft, degPerCell, inflated, (flags and 0x1) != 0)
        }

        private fun ByteArray.leInt(off: Int): Int =
            (this[off].toInt() and 0xFF) or
                ((this[off + 1].toInt() and 0xFF) shl 8) or
                ((this[off + 2].toInt() and 0xFF) shl 16) or
                ((this[off + 3].toInt() and 0xFF) shl 24)

        private fun ByteArray.leDouble(off: Int): Double {
            var bits = 0L
            for (i in 0..7) bits = bits or ((this[off + i].toLong() and 0xFF) shl (i * 8))
            return Double.fromBits(bits)
        }
    }
}
