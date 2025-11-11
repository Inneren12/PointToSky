package dev.pointtosky.core.catalog.binary

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.catalog.star.Star
import dev.pointtosky.core.catalog.star.StarCatalog
import dev.pointtosky.core.catalog.testutil.ByteArrayAssetProvider
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Минимальный тест: при битом/пустом бинаре возвращается переданный fallback.
 */
class BinaryStarCatalogLoadFallbackTest {
    @Test
    fun `invalid header returns provided fallback`() {
        val provider = ByteArrayAssetProvider(mapOf(BinaryStarCatalog.DEFAULT_PATH to ByteArray(0)))
        val sentinel = object : StarCatalog {
            override fun nearby(center: Equatorial, radiusDeg: Double, magLimit: Double?): List<Star> = emptyList()
        }
        val loaded = BinaryStarCatalog.load(provider, fallback = sentinel)
        assertSame(sentinel, loaded)
    }
}
