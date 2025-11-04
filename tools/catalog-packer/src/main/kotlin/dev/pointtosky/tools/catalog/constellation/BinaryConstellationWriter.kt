package dev.pointtosky.tools.catalog.constellation

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32

object BinaryConstellationWriter {
    private const val MAGIC = "PTSKCONS"
    private const val VERSION: Int = 1
    private const val CONSTELLATION_COUNT: Int = 88

    fun write(catalog: PackedCatalog, outputPath: Path) {
        require(catalog.constellations.size == CONSTELLATION_COUNT) {
            "Expected $CONSTELLATION_COUNT constellations, got ${catalog.constellations.size}"
        }

        val polygonCount = catalog.polygonCount
        val vertexCount = catalog.vertexCount

        val directoryBytes = ByteArrayOutputStream()
        val polygonsBytes = ByteArrayOutputStream()
        val verticesBytes = ByteArrayOutputStream()

        var polygonIndex = 0
        var vertexIndex = 0

        catalog.constellations.forEach { constellation ->
            val dirEntry = DirectoryEntry(
                code = constellation.code,
                polyStart = polygonIndex,
                polyCount = constellation.polygons.size,
                aabb = constellation.aabb,
            )
            writeDirectoryEntry(directoryBytes, dirEntry)

            constellation.polygons.forEach { polygon ->
                val polyEntry = PolygonEntry(
                    vertexStart = vertexIndex,
                    vertexCount = polygon.vertices.size,
                    aabb = polygon.aabb,
                )
                writePolygonEntry(polygonsBytes, polyEntry)
                polygon.vertices.forEach { vertex ->
                    writeVertex(verticesBytes, vertex)
                }
                polygonIndex += 1
                vertexIndex += polygon.vertices.size
            }
        }

        val dataBytes = directoryBytes.toByteArray() + polygonsBytes.toByteArray() + verticesBytes.toByteArray()
        val crc = CRC32().apply { update(dataBytes) }.value

        val headerBytes = ByteArrayOutputStream()
        headerBytes.write(MAGIC.toByteArray(Charsets.US_ASCII))
        headerBytes.writeShortLE(VERSION)
        headerBytes.writeShortLE(0)
        headerBytes.writeShortLE(CONSTELLATION_COUNT)
        headerBytes.writeIntLE(polygonCount)
        headerBytes.writeIntLE(vertexCount)
        headerBytes.writeIntLE(crc.toInt())

        val finalBytes = headerBytes.toByteArray() + dataBytes
        Files.write(outputPath, finalBytes)
    }

    private fun writeDirectoryEntry(out: ByteArrayOutputStream, entry: DirectoryEntry) {
        val codeBytes = entry.code.padEnd(3, ' ').substring(0, 3).toByteArray(Charsets.US_ASCII)
        out.write(codeBytes)
        out.write(byteArrayOf(0))
        out.writeIntLE(entry.polyStart)
        out.writeIntLE(entry.polyCount)
        out.writeFloatLE(entry.aabb.raMin.toFloat())
        out.writeFloatLE(entry.aabb.raMax.toFloat())
        out.writeFloatLE(entry.aabb.decMin.toFloat())
        out.writeFloatLE(entry.aabb.decMax.toFloat())
    }

    private fun writePolygonEntry(out: ByteArrayOutputStream, entry: PolygonEntry) {
        out.writeIntLE(entry.vertexStart)
        out.writeIntLE(entry.vertexCount)
        out.writeFloatLE(entry.aabb.raMin.toFloat())
        out.writeFloatLE(entry.aabb.raMax.toFloat())
        out.writeFloatLE(entry.aabb.decMin.toFloat())
        out.writeFloatLE(entry.aabb.decMax.toFloat())
    }

    private fun writeVertex(out: ByteArrayOutputStream, vertex: Vertex) {
        out.writeFloatLE(vertex.ra.toFloat())
        out.writeFloatLE(vertex.dec.toFloat())
    }

    private fun ByteArrayOutputStream.writeShortLE(value: Int) {
        write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(value.toShort()).array())
    }

    private fun ByteArrayOutputStream.writeIntLE(value: Int) {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array())
    }

    private fun ByteArrayOutputStream.writeFloatLE(value: Float) {
        write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(value).array())
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
