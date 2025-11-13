package dev.pointtosky.tools.ephemcli

import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.SimpleEphemerisComputer
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.util.Locale
import kotlin.math.roundToLong
import kotlin.system.exitProcess

private data class CliConfig(
    val instant: Instant,
    val body: Body,
    val step: Duration,
    val count: Int,
)

fun main(args: Array<String>) {
    val config = try {
        parseArgs(args)
    } catch (error: IllegalArgumentException) {
        System.err.println(error.message)
        printUsage()
        exitProcess(1)
    }

    val computer = SimpleEphemerisComputer()

    println("instant,body,raDeg,decDeg,distanceAu,phase")

    var instant = config.instant
    repeat(config.count) { index ->
        val ephemeris = computer.compute(config.body, instant)
        val raDeg = formatDouble(ephemeris.eq.raDeg)
        val decDeg = formatDouble(ephemeris.eq.decDeg)
        val distanceAu = formatDouble(ephemeris.distanceAu)
        val phase = formatDouble(ephemeris.phase)

        println(
            listOf(
                instant.toString(),
                config.body.name,
                raDeg,
                decDeg,
                distanceAu,
                phase,
            ).joinToString(separator = ","),
        )

        if (index < config.count - 1) {
            instant = instant.plus(config.step)
        }
    }
}

private fun parseArgs(args: Array<String>): CliConfig {
    if (args.isEmpty()) {
        throw IllegalArgumentException("No arguments provided")
    }

    val options = buildMap {
        args.forEach { arg ->
            if (!arg.startsWith("--")) {
                throw IllegalArgumentException("Invalid argument format: '$arg'")
            }

            val keyValue = arg.removePrefix("--").split('=', limit = 2)
            if (keyValue.size != 2 || keyValue[1].isEmpty()) {
                throw IllegalArgumentException("Arguments must use --key=value format: '$arg'")
            }

            put(keyValue[0], keyValue[1].trim('"'))
        }
    }

    val instant = options["instant"]?.let {
        try {
            Instant.parse(it)
        } catch (error: DateTimeParseException) {
            throw IllegalArgumentException("Invalid instant value: ${error.parsedString}")
        }
    } ?: throw IllegalArgumentException("--instant argument is required")

    val body = options["body"]?.let {
        try {
            Body.valueOf(it.uppercase(Locale.ROOT))
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Unsupported body: '$it'. Expected one of ${Body.entries.joinToString()}")
        }
    } ?: throw IllegalArgumentException("--body argument is required")

    val count = options["count"]?.toIntOrNull()?.takeIf { it > 0 }
        ?: options["count"]?.let { throw IllegalArgumentException("--count must be a positive integer") }
        ?: 1

    val stepHours = options["stepHours"]?.toDoubleOrNull()?.takeIf { it > 0.0 }
        ?: options["stepHours"]?.let { throw IllegalArgumentException("--stepHours must be a positive number") }
        ?: 1.0

    val step = durationFromHours(stepHours)

    return CliConfig(
        instant = instant,
        body = body,
        step = step,
        count = count,
    )
}

private fun durationFromHours(stepHours: Double): Duration {
    val nanos = (stepHours * 3_600_000_000_000.0).roundToLong()
    return Duration.ofNanos(nanos)
}

private fun formatDouble(value: Double?): String =
    value?.let { String.format(Locale.US, "%.6f", it) } ?: ""

private fun printUsage() {
    System.err.println(
        """
            Usage:
              --instant=ISO_INSTANT   (e.g., 2025-01-01T00:00:00Z)
              --body=SUN|MOON|JUPITER|SATURN
              [--stepHours=HOURS]     (default: 1)
              [--count=NUMBER]        (default: 1)
        """.trimIndent(),
    )
}
