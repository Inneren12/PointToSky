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

/**
 * Status indicating whether real or fake constellation boundaries are in use.
 */
sealed class ConstellationBoundariesStatus {
    /**
     * Successfully loaded real constellation boundaries from binary file.
     */
    data class Real(val metadata: BinaryConstellationBoundaries.Metadata) : ConstellationBoundariesStatus()

    /**
     * Using fake/fallback constellation boundaries due to load failure.
     */
    data class Fake(val reason: String) : ConstellationBoundariesStatus()
}

/**
 * Result of loading constellation boundaries, including status information.
 */
data class BoundariesLoadResult(
    val boundaries: ConstellationBoundaries,
    val status: ConstellationBoundariesStatus,
)

class BinaryConstellationBoundaries private constructor(
    private val regions: List<Region>,
    val metadata: Metadata,
    val isFallback: Boolean = false,
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

    companion object {
        private const val TAG: String = "BinaryConstellation"
        private const val MAGIC_PREFIX: String = "PTSK"
        private const val EXPECTED_TYPE: String = "CONS"
        private const val HEADER_SIZE_BYTES: Int = 20
        private const val SUPPORTED_VERSION: Int = 1
        const val DEFAULT_PATH: String = "catalog/const_v1.bin"

        fun load(
            assetProvider: AssetProvider,
            path: String = DEFAULT_PATH,
            fallback: ConstellationBoundaries = FakeConstellationBoundaries,
            logger: Logger = LogBus,
        ): BoundariesLoadResult {
            // Helper function to return fallback with logging
            fun returnFallback(reason: String, exception: Exception? = null): BoundariesLoadResult {
                if (exception != null) {
                    logger.e(TAG, "Constellation boundaries invalid, falling back to FakeConstellationBoundaries", exception, mapOf("path" to path, "reason" to reason))
                } else {
                    logger.e(TAG, "Constellation boundaries invalid, falling back to FakeConstellationBoundaries", payload = mapOf("path" to path, "reason" to reason))
                }
                return BoundariesLoadResult(
                    boundaries = fallback,
                    status = ConstellationBoundariesStatus.Fake(reason)
                )
            }

            val bytes = try {
                assetProvider.open(path).use { it.readBytes() }
            } catch (ioe: IOException) {
                return returnFallback("Failed to open file", ioe)
            } catch (ex: Exception) {
                return returnFallback("Unexpected error while opening file", ex)
            }

            val header = CatalogHeader.read(bytes) ?: run {
                return returnFallback("Invalid header")
            }
            if (header.type != EXPECTED_TYPE || header.version != SUPPORTED_VERSION) {
                return returnFallback("Unsupported catalog type=${header.type} version=${header.version}")
            }
            if (header.recordCount < 0) {
                return returnFallback("Negative record count=${header.recordCount}")
            }

            val payloadOffset = HEADER_SIZE_BYTES
            if (payloadOffset > bytes.size) {
                return returnFallback("Header larger than file")
            }
            val payload = bytes.copyOfRange(payloadOffset, bytes.size)
            val computedCrc = CRC32().apply { update(payload) }.value and 0xFFFF_FFFFL
            if (computedCrc != header.payloadCrc32) {
                return returnFallback("CRC mismatch expected=${header.payloadCrc32} actual=$computedCrc")
            }

            val regions = try {
                val buffer = ByteBuffer.wrap(bytes, payloadOffset, bytes.size - payloadOffset).order(ByteOrder.LITTLE_ENDIAN)
                parseRegions(buffer, header.recordCount)
            } catch (buf: BufferUnderflowException) {
                return returnFallback("Unexpected EOF while parsing", buf)
            } catch (ex: Exception) {
                return returnFallback("Failed to parse constellation boundaries", ex)
            }

            val metadata = Metadata(
                sizeBytes = bytes.size,
                recordCount = header.recordCount,
                payloadCrc32 = header.payloadCrc32,
            )
            logger.i(
                TAG,
                "Constellation boundaries loaded successfully",
                payload = mapOf(
                    "path" to path,
                    "sizeBytes" to metadata.sizeBytes,
                    "crc32" to metadata.payloadCrc32,
                    "count" to metadata.recordCount,
                ),
            )

            return BoundariesLoadResult(
                boundaries = BinaryConstellationBoundaries(regions, metadata, isFallback = false),
                status = ConstellationBoundariesStatus.Real(metadata)
            )
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
        private data class CatalogHeader(
            val magic: String,
            val type: String,
            val version: Int,
            val recordCount: Int,
            val payloadCrc32: Long,
        ) {
            companion object {
                fun read(bytes: ByteArray): CatalogHeader? {
                    if (bytes.size < HEADER_SIZE_BYTES) return null
                    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val magic = buffer.readAscii(4) ?: return null
                    if (magic != MAGIC_PREFIX) return null
                    val type = buffer.readAscii(4) ?: return null
                    val version = buffer.int
                    val recordCount = buffer.int
                    val crc = buffer.int.toLong() and 0xFFFF_FFFFL
                    return CatalogHeader(magic, type, version, recordCount, crc)
                }

                private fun ByteBuffer.readAscii(length: Int): String? {
                    if (remaining() < length) return null
                    val array = ByteArray(length)
                    get(array)
                    return String(array, StandardCharsets.US_ASCII)
                }
            }
        }
    }
    data class Metadata(
        val sizeBytes: Int,
        val recordCount: Int,
        val payloadCrc32: Long,
    )
}
