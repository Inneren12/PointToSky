package dev.pointtosky.tools.catalog.model

import java.io.ByteArrayOutputStream

enum class CatalogSource {
    BSC,
    HYG,
    ;

    companion object {
        fun from(value: String): CatalogSource = when (value.lowercase()) {
            "bsc", "bscv5" -> BSC
            "hyg" -> HYG
            else -> throw IllegalArgumentException("Unsupported source: $value")
        }
    }
}

data class PackRequest(
    val source: CatalogSource,
    val input: java.nio.file.Path,
    val magLimit: Double,
    val rdpEpsilon: Double,
    val withConCodes: Boolean,
)

data class PackResult(
    val binary: ByteArray,
    val meta: CatalogMeta,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PackResult) return false
        if (!binary.contentEquals(other.binary)) return false
        if (meta != other.meta) return false
        return true
    }

    override fun hashCode(): Int {
        var result = binary.contentHashCode()
        result = 31 * result + meta.hashCode()
        return result
    }

    override fun toString(): String =
        "PackResult(binary=${binary.size} bytes, meta=$meta)"
    }

data class StarInput(
    val source: CatalogSource,
    val raDeg: Double,
    val decDeg: Double,
    val mag: Double,
    val hip: Int,
    val name: String?,
    val bayer: String?,
    val flamsteed: String?,
    val constellation: String?,
)

data class CatalogMeta(
    val source: CatalogSource,
    val inputPath: String,
    val magnitudeLimit: Double,
    val starCount: Int,
    val stringPoolSize: Int,
    val indexOffset: Int,
    val indexSize: Int,
    val crc32: Int,
    val bandCount: Int,
    val indexEntryCount: Int,
    val summary: IndexSummary,
    val rdpEpsilon: Double,
) {
    fun toJson(): String = buildString {
        appendLine("{")
        appendLine("  \"source\": \"${source.name}\",")
        appendLine("  \"input\": \"${escape(inputPath)}\",")
        appendLine("  \"magLimit\": ${magnitudeLimit},")
        appendLine("  \"starCount\": ${starCount},")
        appendLine("  \"stringPoolSize\": ${stringPoolSize},")
        appendLine("  \"indexOffset\": ${indexOffset},")
        appendLine("  \"indexSize\": ${indexSize},")
        appendLine("  \"crc32\": ${Integer.toUnsignedLong(crc32)},")
        appendLine("  \"bandCount\": ${bandCount},")
        appendLine("  \"indexEntries\": ${indexEntryCount},")
        appendLine("  \"rdpEpsilon\": ${rdpEpsilon},")
        appendLine("  \"summary\": {")
        appendLine("    \"minMag\": ${summary.minMagnitude},")
        appendLine("    \"maxMag\": ${summary.maxMagnitude},")
        appendLine("    \"minRa\": ${summary.minRa},")
        appendLine("    \"maxRa\": ${summary.maxRa},")
        appendLine("    \"minDec\": ${summary.minDec},")
        appendLine("    \"maxDec\": ${summary.maxDec}")
        appendLine("  }")
        append('}')
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}

data class IndexSummary(
    val minMagnitude: Double,
    val maxMagnitude: Double,
    val minRa: Double,
    val maxRa: Double,
    val minDec: Double,
    val maxDec: Double,
)

class StringPoolBuilder {
    private val buffer = ByteArrayOutputStream()
    private val offsets = mutableMapOf<String, Int>()

    init {
        offsets[""] = 0
        buffer.write(0)
    }

    fun offsetOrZero(value: String?): Int {
        if (value.isNullOrBlank()) return 0
        return offsets.getOrPut(value) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            val offset = buffer.size()
            buffer.write(bytes)
            buffer.write(0)
            offset
        }
    }

    fun toByteArray(): ByteArray = buffer.toByteArray()
}
