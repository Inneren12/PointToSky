package dev.pointtosky.tools.catalog

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.system.exitProcess
import kotlin.text.Charsets

private data class Options(
    val starsCsv: Path,
    val constCsv: Path,
    val outputDir: Path,
)

private data class StarCsvRecord(
    val id: String,
    val name: String?,
    val raDeg: Double,
    val decDeg: Double,
    val mag: Double?,
)

private data class ConstCsvRecord(
    val iauCode: String,
    val minRaDeg: Double,
    val maxRaDeg: Double,
    val minDecDeg: Double,
    val maxDecDeg: Double,
)

fun main(args: Array<String>) {
    val options = parseArgs(args) ?: run {
        printUsage()
        exitProcess(1)
    }

    val starRecords = readStarCsv(options.starsCsv)
    val constRecords = readConstCsv(options.constCsv)

    options.outputDir.createDirectories()

    val writer = BinaryCatalogWriter()
    val starsBinary = writer.writeStars(starRecords)
    val constBinary = writer.writeConstellations(constRecords)

    Files.write(options.outputDir.resolve("stars_v1.bin"), starsBinary)
    Files.write(options.outputDir.resolve("const_v1.bin"), constBinary)

    println(
        "Catalog packer: wrote ${starRecords.size} stars and ${constRecords.size} constellations to ${options.outputDir}" +
            " (stars_v1.bin, const_v1.bin)"
    )
}

private fun parseArgs(args: Array<String>): Options? {
    var stars: Path? = null
    var constellations: Path? = null
    var output: Path? = null
    var index = 0
    while (index < args.size) {
        when (args[index]) {
            "--stars" -> stars = args.getOrNull(++index)?.let(Path::of)
            "--constellations" -> constellations = args.getOrNull(++index)?.let(Path::of)
            "--out-dir" -> output = args.getOrNull(++index)?.let(Path::of)
            "--help", "-h" -> return null
            else -> {
                println("Unknown argument: ${args[index]}")
                return null
            }
        }
        index++
    }

    if (stars == null || constellations == null || output == null) {
        return null
    }

    if (!stars.exists()) {
        error("Stars CSV not found: $stars")
    }
    if (!constellations.exists()) {
        error("Constellations CSV not found: $constellations")
    }
    if (output.exists() && !output.isDirectory()) {
        error("Output path is not a directory: $output")
    }

    return Options(stars, constellations, output)
}

private fun readStarCsv(path: Path): List<StarCsvRecord> {
    return path.readLines().asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapIndexed { index, line ->
            val parts = line.split(',')
            require(parts.size >= 5) { "Expected 5 columns in ${path.name} at line ${index + 1}" }
            val id = parts[0].trim()
            val name = parts[1].trim().takeIf { it.isNotEmpty() }
            val ra = parts[2].trim().toDouble()
            val dec = parts[3].trim().toDouble()
            val mag = parts[4].trim().takeIf { it.isNotEmpty() }?.toDouble()
            StarCsvRecord(id, name, ra, dec, mag)
        }
        .toList()
}

private fun readConstCsv(path: Path): List<ConstCsvRecord> {
    return path.readLines().asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapIndexed { index, line ->
            val parts = line.split(',')
            require(parts.size >= 5) { "Expected 5 columns in ${path.name} at line ${index + 1}" }
            val code = parts[0].trim().uppercase()
            val minRa = parts[1].trim().toDouble()
            val maxRa = parts[2].trim().toDouble()
            val minDec = parts[3].trim().toDouble()
            val maxDec = parts[4].trim().toDouble()
            ConstCsvRecord(code, minRa, maxRa, minDec, maxDec)
        }
        .toList()
}

private class BinaryCatalogWriter {
    fun writeStars(records: List<StarCsvRecord>): ByteArray {
        val payload = LittleEndianBuffer()
        records.forEach { record ->
            payload.writeString(record.id)
            payload.writeNullableString(record.name)
            payload.writeDouble(record.raDeg)
            payload.writeDouble(record.decDeg)
            payload.writeFloat(record.mag?.toFloat() ?: Float.NaN)
        }
        val payloadBytes = payload.toByteArray()
        val header = createHeader("STAR", records.size, payloadBytes)
        return header + payloadBytes
    }

    fun writeConstellations(records: List<ConstCsvRecord>): ByteArray {
        val payload = LittleEndianBuffer()
        records.forEach { record ->
            payload.writeString(record.iauCode)
            payload.writeDouble(normalizeRa(record.minRaDeg))
            payload.writeDouble(normalizeRa(record.maxRaDeg))
            payload.writeDouble(record.minDecDeg)
            payload.writeDouble(record.maxDecDeg)
        }
        val payloadBytes = payload.toByteArray()
        val header = createHeader("CONS", records.size, payloadBytes)
        return header + payloadBytes
    }

    private fun createHeader(type: String, recordCount: Int, payload: ByteArray): ByteArray {
        val header = LittleEndianBuffer()
        val crc = CRC32().apply { update(payload) }.value.toInt()
        header.writeAscii("PTSK")
        header.writeAscii(type)
        header.writeInt(1)
        header.writeInt(recordCount)
        header.writeInt(crc)
        return header.toByteArray()
    }

    private fun normalizeRa(value: Double): Double {
        var result = value % 360.0
        if (result < 0) {
            result += 360.0
        }
        return result
    }
}

private class LittleEndianBuffer {
    private val bytes = mutableListOf<Byte>()

    fun writeAscii(value: String) {
        require(value.length == 4) { "ASCII header fields must be 4 characters" }
        value.toByteArray(Charsets.US_ASCII).forEach { bytes += it }
    }

    fun writeInt(value: Int) {
        repeat(4) { index ->
            bytes += ((value shr (8 * index)) and 0xFF).toByte()
        }
    }

    fun writeDouble(value: Double) {
        val bits = java.lang.Double.doubleToRawLongBits(value)
        repeat(8) { index ->
            bytes += ((bits shr (8 * index)) and 0xFF).toByte()
        }
    }

    fun writeFloat(value: Float) {
        val bits = java.lang.Float.floatToRawIntBits(value)
        repeat(4) { index ->
            bytes += ((bits shr (8 * index)) and 0xFF).toByte()
        }
    }

    fun writeString(value: String) {
        val data = value.toByteArray(Charsets.UTF_8)
        writeInt(data.size)
        bytes.addAll(data.toList())
    }

    fun writeNullableString(value: String?) {
        if (value == null) {
            writeInt(-1)
            return
        }
        writeString(value)
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}

private fun printUsage() {
    println(
        """
        Usage: catalog-packer --stars <stars.csv> --constellations <constellations.csv> --out-dir <output>

        The tool converts simple CSV inputs into binary catalogs understood by the runtime loader.
        """.trimIndent()
    )
}
