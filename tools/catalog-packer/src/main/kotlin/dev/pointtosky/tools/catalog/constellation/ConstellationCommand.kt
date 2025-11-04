package dev.pointtosky.tools.catalog.constellation

import java.nio.file.Files
import java.nio.file.Path

class ConstellationCommand {
    fun run(args: List<String>) {
        val options = try {
            parseOptions(args)
        } catch (_: SilentExitException) {
            return
        }

        val inputPath = options.input ?: error("--in option is required")
        val outputPath = options.output ?: error("--out option is required")
        val epsilon = options.rdpEpsilon ?: DEFAULT_RDP_EPSILON

        val parser = IauAsciiBoundaryParser()
        val constellations = parser.parse(Files.readAllLines(inputPath))

        val processor = ConstellationProcessor()
        val packed = processor.prepare(constellations, epsilon)

        BinaryConstellationWriter.write(packed, outputPath)
    }

    private fun parseOptions(args: List<String>): Options {
        var input: Path? = null
        var output: Path? = null
        var epsilon: Double? = null

        for (raw in args) {
            when {
                raw.startsWith("--in=") -> input = Path.of(raw.substringAfter('='))
                raw.startsWith("--out=") -> output = Path.of(raw.substringAfter('='))
                raw.startsWith("--rdp-epsilon=") -> epsilon = raw.substringAfter('=').toDoubleOrNull()
                    ?: error("Invalid value for --rdp-epsilon: ${raw.substringAfter('=')}")
                raw == "--help" || raw == "-h" -> {
                    printUsage()
                    throw SilentExitException
                }
                raw.isBlank() -> Unit
                else -> error("Unknown option: $raw")
            }
        }

        return Options(input, output, epsilon)
    }

    private fun printUsage() {
        println(
            """
            catalog-packer const --in=<path> --rdp-epsilon=<deg> --out=<path>
            """.trimIndent(),
        )
    }

    private data class Options(
        val input: Path?,
        val output: Path?,
        val rdpEpsilon: Double?,
    )

    private object SilentExitException : RuntimeException()

    companion object {
        private const val DEFAULT_RDP_EPSILON: Double = 0.05
    }
}
