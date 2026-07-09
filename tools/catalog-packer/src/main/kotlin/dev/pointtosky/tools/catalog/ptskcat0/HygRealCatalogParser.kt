package dev.pointtosky.tools.catalog.ptskcat0

import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import dev.pointtosky.tools.catalog.ValidationConstants
import dev.pointtosky.tools.catalog.csv.CsvRow
import java.nio.file.Path

/**
 * Reads a real HYG catalog CSV (e.g. hyg_v42.csv) into [PtskCat0StarInput] rows.
 *
 * HYG-specific quirks handled here (see docs/star_catalog_ptskcat0_format.md):
 *  - `ra` is in HOURS [0,24), not degrees — multiplied by 15. A self-check refuses
 *    to proceed if the column looks like it's already in degrees, since that
 *    mistake silently mirrors the whole sky.
 *  - `id == 0` is the Sun — excluded.
 *  - `ci` (B-V) is often blank for faint/non-Hipparcos stars — left null; the
 *    writer encodes that as the -32768 sentinel.
 *  - `hip` is often blank for non-Hipparcos stars — normalized to 0.
 *  - Star name prefers `proper` (common name), falling back to `bf`
 *    (combined Bayer/Flamsteed designation) when non-blank.
 */
object HygRealCatalogParser {
    private const val MAX_PLAUSIBLE_RA_HOURS = 24.5

    fun read(path: Path): List<PtskCat0StarInput> {
        val rows = csvReader { skipEmptyLine = true }.readAllWithHeader(path.toFile())

        val rawRaHours = rows.mapNotNull { CsvRow(it).double("ra") }
        val maxRaHours = rawRaHours.maxOrNull() ?: 0.0
        check(maxRaHours <= MAX_PLAUSIBLE_RA_HOURS) {
            "HYG 'ra' column looks like it's already in degrees (max=$maxRaHours > " +
                "$MAX_PLAUSIBLE_RA_HOURS hours) — refusing to multiply by 15. Check the source file."
        }

        var skipped = 0
        val stars = rows.mapNotNull { row ->
            val accessor = CsvRow(row)
            val id = accessor.int("id") ?: return@mapNotNull null
            if (id == 0) return@mapNotNull null // Sun

            val raHours = accessor.double("ra") ?: return@mapNotNull null
            val raDeg = ValidationConstants.normalizeRa(raHours * 15.0)
            val decDeg = accessor.double("dec") ?: return@mapNotNull null
            val mag = accessor.double("mag") ?: return@mapNotNull null

            val error = ValidationConstants.validateStarInput(raDeg, decDeg, mag)
            if (error != null) {
                System.err.println("WARNING [HYG]: Skipping id=$id: $error")
                skipped++
                return@mapNotNull null
            }

            val bv = accessor.double("ci")
            val hip = accessor.int("hip")?.takeIf { it > 0 } ?: 0
            val proper = accessor.string("proper")?.takeIf { it.isNotBlank() }
            val bf = accessor.string("bf")?.takeIf { it.isNotBlank() }
            val name = proper ?: bf

            PtskCat0StarInput(
                id = id,
                raDeg = raDeg,
                decDeg = decDeg,
                mag = mag,
                bv = bv,
                hip = hip,
                name = name,
            )
        }

        if (skipped > 0) {
            System.err.println("INFO [HYG]: Skipped $skipped rows due to validation failures")
        }
        return stars
    }
}
