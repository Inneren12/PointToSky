package dev.pointtosky.tools.catalog.csv

import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import dev.pointtosky.tools.catalog.ValidationConstants
import dev.pointtosky.tools.catalog.model.CatalogSource
import dev.pointtosky.tools.catalog.model.StarInput
import java.nio.file.Path

class HygCatalogParser : CatalogCsvParser {
    private var skippedCount = 0
    private var totalRows = 0

    override fun read(path: Path, magLimit: Double): List<StarInput> {
        val reader = csvReader { skipEmptyLine = true }
        val rows = reader.readAllWithHeader(path.toFile())
        skippedCount = 0
        totalRows = rows.size

        val stars = rows.mapNotNull { row ->
            val accessor = CsvRow(row)
            val mag = accessor.double("mag", "vmag") ?: return@mapNotNull null
            if (mag > magLimit) return@mapNotNull null

            val ra = accessor.double("ra_deg")
                ?: accessor.double("ra")?.let { it * 15.0 }
                ?: return@mapNotNull null
            val dec = accessor.double("dec_deg")
                ?: accessor.double("dec")
                ?: return@mapNotNull null

            // Validate coordinates and magnitude
            val error = ValidationConstants.validateStarInput(ra, dec, mag)
            if (error != null) {
                System.err.println("WARNING [HYG]: Skipping invalid star: $error")
                skippedCount++
                return@mapNotNull null
            }

            val hip = accessor.int("hip") ?: accessor.int("HIP") ?: -1
            val name = accessor.string("proper", "Name")
            val bayer = accessor.string("bayer")
            val flamsteed = accessor.string("flamsteed")
            val con = accessor.string("con", "Con")

            StarInput(
                source = CatalogSource.HYG,
                raDeg = ValidationConstants.normalizeRa(ra),
                decDeg = dec,
                mag = mag,
                hip = hip,
                name = name,
                bayer = bayer,
                flamsteed = flamsteed,
                constellation = con,
            )
        }

        if (skippedCount > 0) {
            System.err.println("INFO [HYG]: Skipped $skippedCount / $totalRows rows due to validation failures")
        }

        return stars
    }
}
