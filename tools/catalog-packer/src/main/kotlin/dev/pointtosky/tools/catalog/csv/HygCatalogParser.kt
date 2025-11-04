package dev.pointtosky.tools.catalog.csv

import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import dev.pointtosky.tools.catalog.model.CatalogSource
import dev.pointtosky.tools.catalog.model.StarInput
import java.nio.file.Path

class HygCatalogParser : CatalogCsvParser {
    override fun read(path: Path, magLimit: Double): List<StarInput> {
        val reader = csvReader { skipEmptyLine = true }
        val rows = reader.readAllWithHeader(path.toFile())
        return rows.mapNotNull { row ->
            val accessor = CsvRow(row)
            val mag = accessor.double("mag", "vmag") ?: return@mapNotNull null
            if (mag > magLimit) return@mapNotNull null

            val ra = accessor.double("ra_deg")
                ?: accessor.double("ra")?.let { it * 15.0 }
                ?: return@mapNotNull null
            val dec = accessor.double("dec_deg")
                ?: accessor.double("dec")
                ?: return@mapNotNull null

            val hip = accessor.int("hip") ?: accessor.int("HIP") ?: -1
            val name = accessor.string("proper", "Name")
            val bayer = accessor.string("bayer")
            val flamsteed = accessor.string("flamsteed")
            val con = accessor.string("con", "Con")

            StarInput(
                source = CatalogSource.HYG,
                raDeg = ra,
                decDeg = dec,
                mag = mag,
                hip = hip,
                name = name,
                bayer = bayer,
                flamsteed = flamsteed,
                constellation = con,
            )
        }
    }
}
