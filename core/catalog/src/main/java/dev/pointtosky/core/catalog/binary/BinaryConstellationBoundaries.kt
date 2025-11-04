package dev.pointtosky.core.catalog.binary

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.identify.ConstellationBoundaries
import dev.pointtosky.core.catalog.fake.FakeConstellationBoundaries
import dev.pointtosky.core.catalog.io.AssetProvider
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.core.logging.Logger
import java.io.IOException
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.CRC32

public class BinaryConstellationBoundaries private constructor(
    private val regions: List<Region>,
) : ConstellationBoundaries {
    override fun findByEq(eq: Equatorial): String? {
        val normalizedRa = normalizeRa(eq.raDeg)
        return regions.firstOrNull { region -> region.contains(normalizedRa, eq.decDeg) }?.iauCode
    }

    private data class Region(
        val iauCode: String,
        val minRaDeg: Double,
        val maxRaDeg: Double,
        val minDecDeg: Double,
        val maxDecDeg: Double,
    ) {
        fun contains(raDeg: Double, decDeg: Double): Boolean {
            val raMatches = if (minRaDeg <= maxRaDeg) {
                raDeg in minRaDeg..maxRaDeg
            } else {
                raDeg >= minRaDeg || raDeg <= maxRaDeg
            }
            val decMatches = decDeg in minDecDeg..maxDecDeg
            return raMatches && decMatches
        }
    }

    public companion object {
        private const val TAG: String = "BinaryConstellation"
        private const val EXPECTED_TYPE: String = "CONS"
        private const val SUPPORTED_VERSION: Int = 1
        public const val DEFAULT_PATH: String = "catalog/const_v1.bin"

        public fun load(
            assetProvider: AssetProvider,
            path: String = DEFAULT_PATH,
            fallback: ConstellationBoundaries = FakeConstellationBoundaries,
            logger: Logger = LogBus,
        ): ConstellationBoundaries {
            val bytes = try {
                assetProvider.open(path).use { it.readBytes() }
            } catch (ioe: IOException) {
                logger.e(TAG, "Failed to open constellation boundaries", ioe, mapOf("path" to path))
                return fallback
            } catch (ex: Exception) {
                logger.e(TAG, "Unexpected error while opening constellation boundaries", ex, mapOf("path" to path))
                return fallback
            }

            val (header, buffer) = BinaryCatalogHeader.read(bytes) ?: run {
                logger.e(TAG, "Invalid header", payload = mapOf("path" to path))
                return fallback
            }
            if (header.type != EXPECTED_TYPE || header.version != SUPPORTED_VERSION) {
                logger.e(
                    TAG,
                    "Unsupported catalog",
                    payload = mapOf("path" to path, "type" to header.type, "version" to header.version),
                )
                return fallback
            }
            if (header.recordCount < 0) {
                logger.e(
                    TAG,
                    "Negative record count",
                    payload = mapOf("path" to path, "count" to header.recordCount),
                )
                return fallback
            }

            val payload = bytes.copyOfRange(BinaryCatalogHeader.HEADER_SIZE_BYTES, bytes.size)
            val computedCrc = CRC32().apply { update(payload) }.value and 0xFFFF_FFFFL
            if (computedCrc != header.payloadCrc32) {
                logger.e(
                    TAG,
                    "CRC mismatch",
                    payload = mapOf(
                        "path" to path,
                        "expectedCrc" to header.payloadCrc32,
                        "actualCrc" to computedCrc,
                    ),
                )
                return fallback
            }

            val regions = try {
                buffer.position(BinaryCatalogHeader.HEADER_SIZE_BYTES)
                parseRegions(buffer, header.recordCount)
            } catch (buf: BufferUnderflowException) {
                logger.e(TAG, "Unexpected EOF while parsing constellation boundaries", buf, mapOf("path" to path))
                return fallback
            } catch (ex: Exception) {
                logger.e(TAG, "Failed to parse constellation boundaries", ex, mapOf("path" to path))
                return fallback
            }

            return BinaryConstellationBoundaries(regions)
        }

        private fun parseRegions(buffer: ByteBuffer, recordCount: Int): List<Region> {
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val regions = ArrayList<Region>(recordCount.coerceAtLeast(0))
            repeat(recordCount.coerceAtLeast(0)) {
                val code = buffer.readUtf8String() ?: throw IllegalStateException("Missing IAU code")
                val minRa = buffer.double
                val maxRa = buffer.double
                val minDec = buffer.double
                val maxDec = buffer.double
                regions += Region(
                    iauCode = code,
                    minRaDeg = normalizeRa(minRa),
                    maxRaDeg = normalizeRa(maxRa),
                    minDecDeg = minDec,
                    maxDecDeg = maxDec,
                )
            }
            return regions
        }

        private fun ByteBuffer.readUtf8String(): String? {
            if (remaining() < Int.SIZE_BYTES) {
                return null
            }
            val length = int
            if (length < 0 || remaining() < length) {
                return null
            }
            val bytes = ByteArray(length)
            get(bytes)
            return String(bytes, StandardCharsets.UTF_8)
        }

        private fun normalizeRa(value: Double): Double {
            var result = value % 360.0
            if (result < 0) {
                result += 360.0
            }
            return result
        }
    }
}
