package dev.pointtosky.tools.catalog

import dev.pointtosky.tools.catalog.constellation.ConstellationCommand
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printUsageAndExit()
    }

    when (val command = args[0]) {
        "const" -> ConstellationCommand().run(args.drop(1))
        "-h", "--help" -> printUsageAndExit()
        else -> {
            System.err.println("Unknown command: $command")
            printUsageAndExit()
        }
    }
}

private fun printUsageAndExit(): Nothing {
    System.err.println(
        """
        Usage:
          catalog-packer const --in=<path> --rdp-epsilon=<deg> --out=<path>
        """.trimIndent(),
    )
    exitProcess(1)
}
