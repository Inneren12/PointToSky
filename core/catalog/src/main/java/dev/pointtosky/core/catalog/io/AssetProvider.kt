package dev.pointtosky.core.catalog.io

import java.io.InputStream

/**
 * Абстракция источника ассетов/ресурсов для каталога.
 * Реализация для Android будет добавлена позже.
 */
interface AssetProvider {
    /** Открыть поток для чтения бинаря по относительному пути (например, "catalog/star.bin"). */
    @Throws(Exception::class)
    fun open(path: String): InputStream

    /** Проверить наличие файла/ресурса. */
    fun exists(path: String): Boolean

    /** Опционально: перечислить файлы в каталоге (может бросать UnsupportedOperationException). */
    fun list(path: String): List<String> = emptyList()
}
