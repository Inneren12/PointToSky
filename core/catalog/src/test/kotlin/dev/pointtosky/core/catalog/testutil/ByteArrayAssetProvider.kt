package dev.pointtosky.core.catalog.testutil

import dev.pointtosky.core.catalog.io.AssetProvider
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * Простой in-memory AssetProvider для unit-тестов.
 */
class ByteArrayAssetProvider(
    private val files: Map<String, ByteArray>,
) : AssetProvider {
    override fun open(path: String): InputStream = files[path]?.let { ByteArrayInputStream(it) } ?: error("No such asset: $path")

    override fun exists(path: String): Boolean = files.containsKey(path)

    override fun list(path: String): List<String> = files.keys.filter { it.startsWith(path) }
}
