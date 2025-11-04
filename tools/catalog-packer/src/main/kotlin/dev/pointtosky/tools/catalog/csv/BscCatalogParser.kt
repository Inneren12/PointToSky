package dev.pointtosky.tools.catalog.csv

import com.github.doyaaaaaken.kotlincsv.dsl.csvReader
import dev.pointtosky.tools.catalog.model.CatalogSource
import dev.pointtosky.tools.catalog.model.StarInput
import java.nio.file.Path
import kotlin.math.abs

class BscCatalogParser : CatalogCsvParser {
    override fun read(path: Path, magLimit: Double): List<StarInput> {
        val reader = csvReader { skipEmptyLine = true }
        val rows = reader.readAllWithHeader(path.toFile())
        return rows.mapNotNull { row ->
            val accessor = CsvRow(row)
            val mag = accessor.double("Vmag", "vmag", "Vmag (Johnson)") ?: return@mapNotNull null
            if (mag > magLimit) return@mapNotNull null

            val raDeg = accessor.raDegrees() ?: return@mapNotNull null
            val decDeg = accessor.decDegrees() ?: return@mapNotNull null

            val hip = accessor.int("HIP", "hip") ?: -1
            val name = accessor.string("Name", "proper", "ProperName")
            val bayer = accessor.string("Bayer", "bayer")
            val flamsteed = accessor.string("Flamsteed", "flamsteed")
            val con = accessor.string("Con", "con", "Constellation")

            StarInput(
                source = CatalogSource.BSC,
                raDeg = raDeg,
                decDeg = decDeg,
                mag = mag,
                hip = hip,
                name = name,
                bayer = bayer,
                flamsteed = flamsteed,
                constellation = con,
            )
        }
    }

    private fun CsvRow.raDegrees(): Double? {
        value("RAdeg", "RA (deg)", "RA_deg", "ra_deg")?.toDoubleOrNull()?.let { return it }

        val rah = value("RAh", "RA (hours)", "RAhour", "rah")?.toDoubleOrNull()
        val ram = value("RAm", "RAmin", "ram")?.toDoubleOrNull() ?: 0.0
        val ras = value("RAs", "RAsec", "ras")?.toDoubleOrNull() ?: 0.0
        if (rah != null) {
            return (rah + ram / 60.0 + ras / 3600.0) * 15.0
        }

        val raGeneric = value("RA", "ra")?.toDoubleOrNull()
        if (raGeneric != null) {
            return if (raGeneric > 24.0) raGeneric else raGeneric * 15.0
        }
        return null
    }

    private fun CsvRow.decDegrees(): Double? {
        value("DEdeg", "DE (deg)", "DEd", "DE_deg", "dec", "decdeg", "Dec (deg)")
            ?.toDoubleOrNull()
            ?.let { return it }

        val decSignStr = value("DE-", "DecSign")?.trim()
        val decSign = when (decSignStr) {
            "-", "-1" -> -1
            else -> 1
        }

        val deg = value("DEd", "ded")?.toDoubleOrNull()
        if (deg != null) {
            val minutes = value("DEm", "dem")?.toDoubleOrNull() ?: 0.0
            val seconds = value("DEs", "des")?.toDoubleOrNull() ?: 0.0
            val absValue = abs(deg) + minutes / 60.0 + seconds / 3600.0
            return if (deg < 0) -absValue else decSign * absValue
        }

        val decGeneric = value("Dec", "DEC", "decl")?.toDoubleOrNull()
        if (decGeneric != null) return decGeneric
        return null
    }
}
