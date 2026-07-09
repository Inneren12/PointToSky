package dev.pointtosky.core.catalog.binary

import dev.pointtosky.core.catalog.io.AssetProvider
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Loads the PTSKCAT0 real-star catalog (HYG-derived; see
 * docs/star_catalog_ptskcat0_format.md). Separate from the curated
 * PTSKCAT4 constellation-art pipeline ([dev.pointtosky.core.astro.catalog.PtskCatalogLoader]);
 * this only exposes the raw [PtskCat0Catalog] for future VF-1/CAM consumers,
 * with no visibility filtering, renderer wiring, or fallback catalog.
 */
interface RealStarCatalogProvider {
    /**
     * @throws RealStarCatalogLoadException if the asset is missing, unreadable, or not a
     *   valid PTSKCAT0 binary.
     */
    fun load(): PtskCat0Catalog
}

/** [RealStarCatalogProvider] backed by an [AssetProvider], reading [DEFAULT_PATH] by default. */
class AssetRealStarCatalogProvider(
    private val assetProvider: AssetProvider,
    private val path: String = DEFAULT_PATH,
) : RealStarCatalogProvider {

    override fun load(): PtskCat0Catalog {
        val bytes = try {
            assetProvider.open(path).use { it.readBytes() }
        } catch (e: FileNotFoundException) {
            throw RealStarCatalogLoadException(
                "PTSKCAT0 real-star catalog asset not found at '$path'. Expected it to be " +
                    "bundled under the consuming app/module assets as '$path' — check " +
                    "mobile/wear asset packaging.",
                e,
            )
        } catch (e: IOException) {
            throw RealStarCatalogLoadException(
                "Failed to read PTSKCAT0 real-star catalog asset '$path': ${e.message}. " +
                    "Check app/module asset packaging.",
                e,
            )
        }

        return try {
            PtskCat0Catalog.parse(bytes)
        } catch (e: IllegalArgumentException) {
            throw RealStarCatalogLoadException(
                "Asset '$path' is not a valid PTSKCAT0 real-star catalog: ${e.message}. " +
                    "Regenerate it via :tools:catalog-packer rather than editing it by hand.",
                e,
            )
        }
    }

    companion object {
        const val DEFAULT_PATH = "catalog/stars_real.bin"
    }
}

/** Thrown by [RealStarCatalogProvider.load] when the PTSKCAT0 asset can't be loaded or parsed. */
class RealStarCatalogLoadException(message: String, cause: Throwable? = null) : Exception(message, cause)
