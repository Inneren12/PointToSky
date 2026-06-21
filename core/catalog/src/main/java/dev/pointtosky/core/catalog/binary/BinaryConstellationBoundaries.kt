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
import java.util.zip.CRC32
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min

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
    private val constellations: List<RuntimeConstellation>,
    val metadata: Metadata,
) : ConstellationBoundaries {

    override fun findByEq(eq: Equatorial): String? {
        val ra = normalizeRa(eq.raDeg)
        val dec = eq.decDeg
        for (constellation in constellations) {
            if (!constellation.aabb.contains(ra, dec)) continue
            for (polygon in constellation.polygons) {
                if (!polygon.aabb.contains(ra, dec)) continue
                if (polygon.contains(ra, dec)) return constellation.code
            }
        }
        return null
    }

    private data class RuntimeConstellation(
        val code: String,
        val aabb: Aabb,
        val polygons: List<RuntimePolygon>,
    )

    private class RuntimePolygon(
        val aabb: Aabb,
        val unwrappedRa: DoubleArray,
        val dec: DoubleArray,
    ) {
        fun contains(ra: Double, decValue: Double): Boolean {
            if (unwrappedRa.isEmpty()) return false
            var sum = 0.0
            var prevX = angleDelta(unwrappedRa.last(), ra)
            var prevY = dec.last() - decValue
            for (i in unwrappedRa.indices) {
                val x = angleDelta(unwrappedRa[i], ra)
                val y = dec[i] - decValue
                val cross = prevX * y - x * prevY
                val dot = prevX * x + prevY * y
                sum += atan2(cross, dot)
                prevX = x
                prevY = y
            }
            return abs(sum) > Math.PI
        }

        private fun angleDelta(vertexRa: Double, refRa: Double): Double {
            var d = (vertexRa - refRa) % 360.0
            if (d < -180.0) d += 360.0
            if (d >= 180.0) d -= 360.0
            return d
        }
    }

    private data class Aabb(
        val raMin: Float,
        val raMax: Float,
        val decMin: Float,
        val decMax: Float,
    ) {
        private val wraps: Boolean = raMin > raMax

        fun contains(ra: Double, dec: Double): Boolean {
            if (dec < decMin - TOLERANCE || dec > decMax + TOLERANCE) return false
            return if (!wraps) {
                ra >= raMin - TOLERANCE && ra <= raMax + TOLERANCE
            } else {
                ra >= raMin - TOLERANCE || ra <= raMax + TOLERANCE
            }
        }
    }

    companion object {
        private const val TAG = "BinaryConstellation"
        private const val MAGIC = "PTSKCONS"
        // 8(magic) + 2(version) + 2(reserved) + 2(constCount) + 4(polyCount) + 4(vertexCount) + 4(crc32)
        private const val HEADER_SIZE = 26
        private const val SUPPORTED_VERSION = 1
        private const val TOLERANCE = 1e-5
        const val DEFAULT_PATH = "catalog/const_v1.bin"

        fun load(
            assetProvider: AssetProvider,
            path: String = DEFAULT_PATH,
            fallback: ConstellationBoundaries = FakeConstellationBoundaries,
            logger: Logger = LogBus,
        ): BoundariesLoadResult {
            fun returnFallback(reason: String, exception: Exception? = null): BoundariesLoadResult {
                if (exception != null) {
                    logger.e(TAG, "Constellation boundaries invalid, falling back to FakeConstellationBoundaries", exception, mapOf("path" to path, "reason" to reason))
                } else {
                    logger.e(TAG, "Constellation boundaries invalid, falling back to FakeConstellationBoundaries", payload = mapOf("path" to path, "reason" to reason))
                }
                return BoundariesLoadResult(boundaries = fallback, status = ConstellationBoundariesStatus.Fake(reason))
            }

            val bytes = try {
                assetProvider.open(path).use { it.readBytes() }
            } catch (ioe: IOException) {
                return returnFallback("Failed to open file", ioe)
            } catch (ex: Exception) {
                return returnFallback("Unexpected error while opening file", ex)
            }

            if (bytes.size < HEADER_SIZE) {
                return returnFallback("Invalid header")
            }

            val hdr = ByteBuffer.wrap(bytes, 0, HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
            val magicBytes = ByteArray(8)
            hdr.get(magicBytes)
            if (magicBytes.toString(Charsets.US_ASCII) != MAGIC) {
                return returnFallback("Invalid header")
            }
            val version = hdr.short.toInt() and 0xFFFF
            if (version != SUPPORTED_VERSION) {
                return returnFallback("Unsupported catalog version=$version")
            }
            hdr.short // reserved
            val constellationCount = hdr.short.toInt() and 0xFFFF
            val polygonCount = hdr.int
            val vertexCount = hdr.int
            val storedCrc = hdr.int.toLong() and 0xFFFF_FFFFL

            if (polygonCount < 0 || vertexCount < 0) {
                return returnFallback("Negative counts in header")
            }

            val payload = bytes.copyOfRange(HEADER_SIZE, bytes.size)
            val computedCrc = CRC32().apply { update(payload) }.value and 0xFFFF_FFFFL
            if (computedCrc != storedCrc) {
                return returnFallback("CRC mismatch expected=$storedCrc actual=$computedCrc")
            }

            val constellations = try {
                val buffer = ByteBuffer.wrap(bytes, HEADER_SIZE, bytes.size - HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                parseConstellations(buffer, constellationCount, polygonCount, vertexCount)
            } catch (buf: BufferUnderflowException) {
                return returnFallback("Unexpected EOF while parsing", buf)
            } catch (ex: Exception) {
                return returnFallback("Failed to parse constellation boundaries", ex)
            }

            val metadata = Metadata(
                sizeBytes = bytes.size,
                recordCount = constellationCount,
                payloadCrc32 = storedCrc,
            )
            logger.i(TAG, "Constellation boundaries loaded successfully", payload = mapOf(
                "path" to path,
                "sizeBytes" to metadata.sizeBytes,
                "crc32" to metadata.payloadCrc32,
                "count" to metadata.recordCount,
            ))

            return BoundariesLoadResult(
                boundaries = BinaryConstellationBoundaries(constellations, metadata),
                status = ConstellationBoundariesStatus.Real(metadata),
            )
        }

        private fun parseConstellations(
            buffer: ByteBuffer,
            constellationCount: Int,
            polygonCount: Int,
            vertexCount: Int,
        ): List<RuntimeConstellation> {
            val directories = ArrayList<DirectoryEntry>(constellationCount)
            repeat(constellationCount) {
                val codeBytes = ByteArray(4)
                buffer.get(codeBytes)
                val code = codeBytes.takeWhile { it != 0.toByte() }
                    .toByteArray()
                    .toString(Charsets.US_ASCII)
                    .trim()
                val polyStart = buffer.int
                val polyCountEntry = buffer.int
                val raMin = buffer.float
                val raMax = buffer.float
                val decMin = buffer.float
                val decMax = buffer.float
                directories += DirectoryEntry(code, polyStart, polyCountEntry, Aabb(raMin, raMax, decMin, decMax))
            }

            val polygonEntries = ArrayList<PolygonEntry>(polygonCount)
            repeat(polygonCount) {
                val vertexStart = buffer.int
                val vCount = buffer.int
                val raMin = buffer.float
                val raMax = buffer.float
                val decMin = buffer.float
                val decMax = buffer.float
                polygonEntries += PolygonEntry(vertexStart, vCount, Aabb(raMin, raMax, decMin, decMax))
            }

            val vertices = FloatArray(vertexCount * 2)
            for (i in 0 until vertexCount) {
                vertices[i * 2] = buffer.float
                vertices[i * 2 + 1] = buffer.float
            }

            val runtimePolygons = polygonEntries.map { buildRuntimePolygon(it, vertices) }

            return directories.map { entry ->
                val start = entry.polyStart
                val end = start + entry.polyCount
                val subset = if (start in runtimePolygons.indices) {
                    runtimePolygons.subList(start, min(end, runtimePolygons.size))
                } else {
                    emptyList()
                }
                RuntimeConstellation(entry.code, entry.aabb, subset)
            }
        }

        private fun buildRuntimePolygon(entry: PolygonEntry, vertices: FloatArray): RuntimePolygon {
            val count = entry.vertexCount
            if (count <= 0) return RuntimePolygon(entry.aabb, DoubleArray(0), DoubleArray(0))

            val start = entry.vertexStart
            val raValues = DoubleArray(count) { normalizeRa(vertices[(start + it) * 2].toDouble()) }
            val decValues = DoubleArray(count) { vertices[(start + it) * 2 + 1].toDouble() }

            // Remove duplicate closing vertex if present
            var actualCount = count
            if (actualCount >= 2 &&
                abs(raValues[0] - raValues[actualCount - 1]) < 1e-6 &&
                abs(decValues[0] - decValues[actualCount - 1]) < 1e-6
            ) {
                actualCount -= 1
            }

            return RuntimePolygon(entry.aabb, unwrap(raValues.copyOf(actualCount)), decValues.copyOf(actualCount))
        }

        private fun unwrap(raValues: DoubleArray): DoubleArray {
            if (raValues.isEmpty()) return raValues
            val result = DoubleArray(raValues.size)
            result[0] = raValues[0]
            var previous = raValues[0]
            for (i in 1 until raValues.size) {
                var value = raValues[i]
                val diff = value - previous
                if (diff > 180) value -= 360.0
                else if (diff < -180) value += 360.0
                result[i] = value
                previous = value
            }
            return result
        }

        private fun normalizeRa(value: Double): Double {
            var ra = value % 360.0
            if (ra < 0) ra += 360.0
            return if (ra == 360.0) 0.0 else ra
        }

        private data class DirectoryEntry(
            val code: String,
            val polyStart: Int,
            val polyCount: Int,
            val aabb: Aabb,
        )

        private data class PolygonEntry(
            val vertexStart: Int,
            val vertexCount: Int,
            val aabb: Aabb,
        )
    }

    data class Metadata(
        val sizeBytes: Int,
        val recordCount: Int,
        val payloadCrc32: Long,
    )
}
