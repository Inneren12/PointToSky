package dev.pointtosky.core.astro.ephem

import dev.pointtosky.core.astro.coord.Equatorial
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant

private const val GOLDEN_RESOURCE = "ephem_golden_v1.json"
private val json = Json {
    prettyPrint = true
    prettyPrintIndent = "  "
    encodeDefaults = false
}

internal fun readGolden(): EphemGolden {
    val stream = goldenResourceStream()
    val payload = stream.bufferedReader().use { it.readText() }
    val dto = json.decodeFromString(EphemGoldenFileDto.serializer(), payload)
    return dto.toDomain()
}

internal fun writeGolden(golden: EphemGolden) {
    val dto = golden.toDto()
    val output = goldenOutputPath()
    Files.createDirectories(output.parent)
    val rendered = json.encodeToString(EphemGoldenFileDto.serializer(), dto)
    Files.writeString(output, "$rendered\n")
}

private fun goldenResourceStream(): InputStream {
    return checkNotNull(EphemGolden::class.java.getResourceAsStream("/$GOLDEN_RESOURCE")) {
        "Missing golden resource $GOLDEN_RESOURCE"
    }
}

private fun goldenOutputPath(): Path = Paths.get("src", "test", "resources", GOLDEN_RESOURCE)

internal data class EphemGolden(val dates: List<GoldenDate>) {
    fun toSamples(): List<GoldenSample> = dates.flatMap { date ->
        listOf(
            GoldenSample(
                body = Body.SUN,
                instant = date.instant,
                expected = Equatorial(date.sun.raDeg, date.sun.decDeg),
                expectedDistanceAu = date.sun.distAu,
                expectedPhase = null,
            ),
            GoldenSample(
                body = Body.MOON,
                instant = date.instant,
                expected = Equatorial(date.moon.raDeg, date.moon.decDeg),
                expectedDistanceAu = date.moon.distAu,
                expectedPhase = date.moon.phase,
            ),
            GoldenSample(
                body = Body.JUPITER,
                instant = date.instant,
                expected = Equatorial(date.jupiter.raDeg, date.jupiter.decDeg),
                expectedDistanceAu = date.jupiter.distAu,
                expectedPhase = null,
            ),
            GoldenSample(
                body = Body.SATURN,
                instant = date.instant,
                expected = Equatorial(date.saturn.raDeg, date.saturn.decDeg),
                expectedDistanceAu = date.saturn.distAu,
                expectedPhase = null,
            ),
        )
    }

    fun recompute(computer: SimpleEphemerisComputer): EphemGolden = EphemGolden(
        dates = dates.map { date ->
            val sun = computer.compute(Body.SUN, date.instant)
            val moon = computer.compute(Body.MOON, date.instant)
            val jupiter = computer.compute(Body.JUPITER, date.instant)
            val saturn = computer.compute(Body.SATURN, date.instant)
            GoldenDate(
                instant = date.instant,
                sun = GoldenBody(
                    raDeg = sun.eq.raDeg,
                    decDeg = sun.eq.decDeg,
                    distAu = requireNotNull(sun.distanceAu) { "Sun distance expected" },
                ),
                moon = GoldenMoon(
                    raDeg = moon.eq.raDeg,
                    decDeg = moon.eq.decDeg,
                    distAu = requireNotNull(moon.distanceAu) { "Moon distance expected" },
                    phase = requireNotNull(moon.phase) { "Moon phase expected" },
                ),
                jupiter = GoldenBody(
                    raDeg = jupiter.eq.raDeg,
                    decDeg = jupiter.eq.decDeg,
                    distAu = requireNotNull(jupiter.distanceAu) { "Jupiter distance expected" },
                ),
                saturn = GoldenBody(
                    raDeg = saturn.eq.raDeg,
                    decDeg = saturn.eq.decDeg,
                    distAu = requireNotNull(saturn.distanceAu) { "Saturn distance expected" },
                ),
            )
        },
    )
}

internal data class GoldenDate(
    val instant: Instant,
    val sun: GoldenBody,
    val moon: GoldenMoon,
    val jupiter: GoldenBody,
    val saturn: GoldenBody,
)

internal data class GoldenBody(
    val raDeg: Double,
    val decDeg: Double,
    val distAu: Double,
)

internal data class GoldenMoon(
    val raDeg: Double,
    val decDeg: Double,
    val distAu: Double,
    val phase: Double,
)

private fun EphemGoldenFileDto.toDomain(): EphemGolden = EphemGolden(
    dates = dates.map { date ->
        GoldenDate(
            instant = Instant.parse(date.instant),
            sun = date.sun.toDomain(),
            moon = date.moon.toDomain(),
            jupiter = date.jupiter.toDomain(),
            saturn = date.saturn.toDomain(),
        )
    },
)

private fun GoldenBodyDto.toDomain(): GoldenBody = GoldenBody(
    raDeg = raDeg,
    decDeg = decDeg,
    distAu = distAu,
)

private fun GoldenMoonDto.toDomain(): GoldenMoon = GoldenMoon(
    raDeg = raDeg,
    decDeg = decDeg,
    distAu = distAu ?: error("Moon distance must be provided"),
    phase = phase,
)

private fun EphemGolden.toDto(): EphemGoldenFileDto = EphemGoldenFileDto(
    dates = dates.map { date ->
        GoldenDateDto(
            instant = date.instant.toString(),
            sun = date.sun.toDto(),
            moon = date.moon.toDto(),
            jupiter = date.jupiter.toDto(),
            saturn = date.saturn.toDto(),
        )
    },
)

private fun GoldenBody.toDto(): GoldenBodyDto = GoldenBodyDto(
    raDeg = raDeg,
    decDeg = decDeg,
    distAu = distAu,
)

private fun GoldenMoon.toDto(): GoldenMoonDto = GoldenMoonDto(
    raDeg = raDeg,
    decDeg = decDeg,
    distAu = distAu,
    phase = phase,
)

@Serializable
private data class EphemGoldenFileDto(
    val dates: List<GoldenDateDto>,
)

@Serializable
private data class GoldenDateDto(
    val instant: String,
    val sun: GoldenBodyDto,
    val moon: GoldenMoonDto,
    val jupiter: GoldenBodyDto,
    val saturn: GoldenBodyDto,
)

@Serializable
private data class GoldenBodyDto(
    val raDeg: Double,
    val decDeg: Double,
    val distAu: Double,
)

@Serializable
private data class GoldenMoonDto(
    val raDeg: Double,
    val decDeg: Double,
    val phase: Double,
    val distAu: Double? = null,
)
