package dev.pointtosky.core.catalog

import dev.pointtosky.core.catalog.io.AssetProvider
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream

/**
 * Простейший AssetProvider для JVM-тестов: отдаёт заранее переданные байты по пути.
 */
internal class ByteArrayAssetProvider(
    private val files: Map<String, ByteArray>,
) : AssetProvider {
    override fun open(path: String): InputStream {
        val data = files[path] ?: throw FileNotFoundException("No test data for path: $path")
        return ByteArrayInputStream(data)
    }
    override fun exists(path: String): Boolean = files.containsKey(path)
    override fun list(path: String): List<String> = files.keys.filter { it.startsWith(path) }
}
