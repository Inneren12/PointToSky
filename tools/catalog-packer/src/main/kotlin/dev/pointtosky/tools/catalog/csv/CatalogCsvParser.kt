package dev.pointtosky.tools.catalog.csv

import dev.pointtosky.tools.catalog.model.StarInput
import java.nio.file.Path

interface CatalogCsvParser {
    fun read(path: Path, magLimit: Double): List<StarInput>
}
