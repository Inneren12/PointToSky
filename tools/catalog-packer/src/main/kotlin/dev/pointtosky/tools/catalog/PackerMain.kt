package dev.pointtosky.tools.catalog

import dev.pointtosky.tools.catalog.model.CatalogSource
import dev.pointtosky.tools.catalog.model.PackRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolute

fun main(args: Array<String>) {
    val cli = try {
        CliParser.parse(args)
    } catch (ex: IllegalArgumentException) {
        System.err.println("Error: ${ex.message}")
        System.err.println(CliParser.usage())
        return
    }

    val request = PackRequest(
        source = cli.source,
        input = cli.input,
        magLimit = cli.magLimit,
        rdpEpsilon = cli.rdpEpsilon,
        withConCodes = cli.withConCodes,
    )

    val service = CatalogPackingService()
    val result = try {
        service.pack(request)
    } catch (ex: Exception) {
        System.err.println("Packing failed: ${ex.message}")
        ex.printStackTrace(System.err)
        return
    }

    val outputPath = cli.output
    val metaPath = outputPath.resolveSibling(outputPath.fileName.toString().removeSuffix(".bin") + ".meta.json")

    outputPath.parent?.let { Files.createDirectories(it) }
    Files.write(outputPath, result.binary)
    Files.writeString(metaPath, result.meta.toJson())

    println("catalog-packer: wrote ${result.binary.size} bytes to ${outputPath.absolute()}")
    println("catalog-packer: metadata -> ${metaPath.absolute()}")
}

internal data class CliOptions(
    val source: CatalogSource,
    val input: Path,
    val output: Path,
    val magLimit: Double,
    val rdpEpsilon: Double,
    val withConCodes: Boolean,
)

internal object CliParser {
    fun parse(args: Array<String>): CliOptions {
        if (args.isEmpty()) {
            throw IllegalArgumentException("no arguments provided")
        }

        val values = mutableMapOf<String, String>()
        val switches = mutableSetOf<String>()
        var index = 0
        while (index < args.size) {
            val token = args[index]
            if (token.startsWith("--")) {
                val nameValue = token.removePrefix("--")
                val name: String
                val value: String?
                if (nameValue.contains('=')) {
                    val parts = nameValue.split('=', limit = 2)
                    name = parts[0]
                    value = parts.getOrNull(1)
                } else {
                    name = nameValue
                    val next = args.getOrNull(index + 1)
                    value = if (next != null && !next.startsWith("-")) {
                        index += 1
                        next
                    } else {
                        null
                    }
                }

                if (value == null) {
                    switches.add(name)
                } else {
                    values[name] = value
                }
            }
            index += 1
        }

        val source = values["source"]?.let { CatalogSource.from(it) }
            ?: throw IllegalArgumentException("--source=bsc|hyg is required")
        val input = values["input"]?.let { Path.of(it) }
            ?: throw IllegalArgumentException("--input=/path/to/catalog.csv is required")
        val output = values["out"]?.let { Path.of(it) }
            ?: throw IllegalArgumentException("--out=/path/to/stars_v1.bin is required")
        val magLimit = values["mag-limit"]?.toDoubleOrNull() ?: DEFAULT_MAG_LIMIT
        val rdpEpsilon = values["rdp-epsilon"]?.toDoubleOrNull() ?: DEFAULT_RDP_EPSILON
        val withConCodes = switches.contains("with-con-codes") || values["with-con-codes"]?.toBoolean() == true

        return CliOptions(
            source = source,
            input = input,
            output = output,
            magLimit = magLimit,
            rdpEpsilon = rdpEpsilon,
            withConCodes = withConCodes,
        )
    }

    fun usage(): String = buildString {
        appendLine("Usage: catalog-packer --source=bsc|hyg --input=/path/file.csv --out=/path/stars_v1.bin [options]")
        appendLine("Options:")
        appendLine("  --mag-limit=6.5       Limit by apparent magnitude (default 6.5)")
        appendLine("  --rdp-epsilon=0.05    Placeholder flag reserved for boundary simplification")
        appendLine("  --with-con-codes      Preserve constellation codes when present")
    }
}

private const val DEFAULT_MAG_LIMIT = 6.5
private const val DEFAULT_RDP_EPSILON = 0.05
