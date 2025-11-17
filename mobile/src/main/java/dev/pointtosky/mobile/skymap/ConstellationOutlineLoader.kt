package dev.pointtosky.mobile.skymap

import android.content.res.AssetManager
import dev.pointtosky.core.astro.coord.Equatorial
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

internal class ConstellationOutlineLoader(
    private val assets: AssetManager,
    private val path: String = DEFAULT_PATH,
) {
    fun load(): List<ConstellationOutline> {
        assets.open(path).use { stream ->
            val bytes = stream.readBytes()
            return runCatching { parse(bytes) }
                .onFailure { e ->
                    // тут можно залогировать, но не падать:
                    android.util.Log.e("ConstellationOutlineLoader", "Failed to parse $path", e)
                }
                .getOrElse { emptyList() }
        }
    }

    private fun parse(bytes: ByteArray): List<ConstellationOutline> {
        require(bytes.size >= HEADER_SIZE_BYTES) { "Binary constellation file is too small" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magicBytes = ByteArray(8)
        buffer.get(magicBytes)
        val magic = magicBytes.toString(Charsets.US_ASCII)
        require(magic == MAGIC) { "Unexpected magic: $magic" }

        val version = buffer.short.toInt() and 0xFFFF
        require(version == VERSION) { "Unsupported version: $version" }
        buffer.short // reserved
        val constellationCount = buffer.short.toInt() and 0xFFFF
        val polygonCount = buffer.int
        val vertexCount = buffer.int
        val storedCrc = buffer.int

        // Вместо жёсткого "88" — просто разумная проверка диапазона:
        require(constellationCount in 1..256) {
            "Constellation count must be in 1..256, got $constellationCount"
        }
        require(polygonCount >= 0) { "Negative polygon count: $polygonCount" }
        require(vertexCount >= 0) { "Negative vertex count: $vertexCount" }
        val dataOffset = buffer.position()
        val payload = bytes.copyOfRange(dataOffset, bytes.size)
        val computedCrc = CRC32().apply { update(payload) }.value.toInt()
        require(computedCrc == storedCrc) { "CRC mismatch" }

        val directories =
            List(constellationCount) {
                val codeBytes = ByteArray(4)
                buffer.get(codeBytes)
                val code =
                    codeBytes
                        .takeWhile { byte -> byte != 0.toByte() }
                        .toByteArray()
                        .toString(Charsets.US_ASCII)
                        .trim()
                val polyStart = buffer.int
                val polyCountEntry = buffer.int
                // Skip the directory AABB, outlines don't need the bounding boxes
                buffer.float
                buffer.float
                buffer.float
                buffer.float
                DirectoryEntry(code, polyStart, polyCountEntry)
            }

        val polygons =
            List(polygonCount) {
                val vertexStart = buffer.int
                val vertexCountEntry = buffer.int
                // Skip per-polygon AABB values (8 bytes RA + 8 bytes Dec)
                buffer.float
                buffer.float
                buffer.float
                buffer.float
                PolygonEntry(vertexStart, vertexCountEntry)
            }

        val vertices = FloatArray(vertexCount * 2)
        for (i in 0 until vertexCount) {
            vertices[i * 2] = buffer.float
            vertices[i * 2 + 1] = buffer.float
        }

        return directories.map { entry ->
            val start = entry.polyStart
            val end = (entry.polyStart + entry.polyCount).coerceAtMost(polygons.size)
            val outlines = mutableListOf<List<Equatorial>>()
            for (index in start until end) {
                val polygon = polygons[index]
                if (polygon.vertexCount <= 1) continue
                val points = ArrayList<Equatorial>(polygon.vertexCount)
                for (vertexIndex in 0 until polygon.vertexCount) {
                    val offset = (polygon.vertexStart + vertexIndex) * 2
                    val ra = vertices[offset].toDouble()
                    val dec = vertices[offset + 1].toDouble()
                    points += Equatorial(raDeg = ra, decDeg = dec)
                }
                outlines += points
            }
            ConstellationOutline(entry.code, outlines)
        }
    }

    private data class DirectoryEntry(
        val code: String,
        val polyStart: Int,
        val polyCount: Int,
    )

    private data class PolygonEntry(
        val vertexStart: Int,
        val vertexCount: Int,
    )

    companion object {
        private const val MAGIC = "PTSKCONS"
        private const val VERSION = 1
            //private const val CONSTELLATION_COUNT = 88
        private const val HEADER_SIZE_BYTES = 8 + 2 + 2 + 2 + 4 + 4 + 4
        private const val DEFAULT_PATH = "catalog/const_v1.bin"
    }
}

data class ConstellationOutline(
    val iauCode: String,
    val polygons: List<List<Equatorial>>,
)
