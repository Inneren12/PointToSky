package dev.pointtosky.core.catalog.runtime

import android.content.Context
import android.content.res.AssetManager
import dev.pointtosky.core.astro.catalog.AstroCatalog
import dev.pointtosky.core.astro.catalog.ConstellationId
import dev.pointtosky.core.astro.catalog.EmptyAstroCatalog
import dev.pointtosky.core.astro.catalog.PtskCatalogLoader
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.identify.ConstellationBoundaries
import dev.pointtosky.core.astro.identify.IdentifySolver
import dev.pointtosky.core.astro.identify.angularSeparationDeg
import dev.pointtosky.core.catalog.CatalogAdapter
import dev.pointtosky.core.catalog.binary.BinaryConstellationBoundaries
import dev.pointtosky.core.catalog.io.AndroidAssetProvider
import dev.pointtosky.core.catalog.io.AssetProvider
import dev.pointtosky.core.catalog.star.AstroStarCatalogAdapter
import dev.pointtosky.core.catalog.star.Star
import dev.pointtosky.core.catalog.star.StarCatalog
import kotlin.math.roundToInt
import kotlin.system.measureNanoTime
import kotlinx.coroutines.runBlocking

class CatalogRepository private constructor(
    val starCatalog: StarCatalog,
    val starMetadata: AstroCatalogStats?,
    val starLoadDurationMs: Long,
    val constellationBoundaries: ConstellationBoundaries,
    val boundaryMetadata: BinaryConstellationBoundaries.Metadata?,
    val boundaryLoadDurationMs: Long,
    val skyCatalog: CatalogAdapter,
    val identifySolver: IdentifySolver,
) {
    val diagnostics: CatalogDiagnostics = CatalogDiagnostics(
        starMetadata = starMetadata,
        starLoadDurationMs = starLoadDurationMs,
        boundaryMetadata = boundaryMetadata,
        boundaryLoadDurationMs = boundaryLoadDurationMs,
        usingBinaryBoundaries = boundaryMetadata != null,
    )

    fun probe(center: Equatorial, radiusDeg: Double, magLimit: Double?, maxResults: Int = 8): List<ProbeResult> {
        if (radiusDeg <= 0.0) return emptyList()
        val matches = starCatalog.nearby(center, radiusDeg, magLimit)
            .take(maxResults)
        return matches.map { star ->
            val separation = angularSeparationDeg(center, Equatorial(star.raDeg.toDouble(), star.decDeg.toDouble()))
            ProbeResult(
                id = star.id,
                name = star.name,
                designation = star.designationLabel(),
                magnitude = star.mag.toDouble(),
                separationDeg = separation,
                constellation = star.constellation,
            )
        }
    }

    fun runSelfTest(): List<SelfTestResult> {
        val cases = listOf(
            SelfTestCase(
                name = "Sirius proximity",
                center = Equatorial(101.3, -16.7),
                radiusDeg = 1.0,
                expectedId = 32349,
            ),
            SelfTestCase(
                name = "Arcturus proximity",
                center = Equatorial(213.9, 19.2),
                radiusDeg = 1.0,
                expectedId = 69673,
            ),
            SelfTestCase(
                name = "Rigel proximity",
                center = Equatorial(78.6, -8.2),
                radiusDeg = 1.0,
                expectedId = 24436,
            ),
        )
        val starResults = cases.map { case ->
            val found = probe(case.center, case.radiusDeg, magLimit = 2.0, maxResults = 1)
            val pass = found.firstOrNull()?.id == case.expectedId
            val detail = found.firstOrNull()?.let { result ->
                "hit=${result.id}, sep=${"%.2f".format(result.separationDeg)}°"
            } ?: "no-match"
            SelfTestResult(case.name, pass, detail)
        }
        val constellationCheck = run {
            val point = Equatorial(90.0, 0.0)
            val iau = constellationBoundaries.findByEq(point)
            val pass = iau == "ORI"
            val detail = iau ?: "null"
            SelfTestResult("Constellation lookup", pass, detail)
        }
        return starResults + constellationCheck
    }

    private fun Star.designationLabel(): String? {
        return when {
            !bayer.isNullOrBlank() && !constellation.isNullOrBlank() -> "${bayer.uppercase()} ${constellation.uppercase()}"
            !flamsteed.isNullOrBlank() && !constellation.isNullOrBlank() -> "$flamsteed ${constellation.uppercase()}"
            !bayer.isNullOrBlank() -> bayer.uppercase()
            !flamsteed.isNullOrBlank() -> flamsteed
            else -> null
        }
    }

    data class ProbeResult(
        val id: Int,
        val name: String?,
        val designation: String?,
        val magnitude: Double,
        val separationDeg: Double,
        val constellation: String?,
    )

    data class SelfTestResult(
        val name: String,
        val passed: Boolean,
        val detail: String,
    )

    private data class SelfTestCase(
        val name: String,
        val center: Equatorial,
        val radiusDeg: Double,
        val expectedId: Int,
    )

    companion object {
        fun create(context: Context): CatalogRepository {
            val provider = AndroidAssetProvider(context.applicationContext)
            val starHolder = loadStars(context.assets)
            val boundariesHolder = loadBoundaries(provider)
            val adapter = CatalogAdapter(
                starHolder.catalog,
                // тип уже ConstellationBoundaries из astro.identify
                boundariesHolder.catalog,
            )
            val solver = IdentifySolver(adapter, adapter)
            return CatalogRepository(
                starCatalog = starHolder.catalog,
                starMetadata = starHolder.metadata,
                starLoadDurationMs = starHolder.loadDurationMs,
                constellationBoundaries = boundariesHolder.catalog,
                boundaryMetadata = boundariesHolder.metadata,
                boundaryLoadDurationMs = boundariesHolder.loadDurationMs,
                skyCatalog = adapter,
                identifySolver = solver,
            )
        }

        private fun loadStars(assetManager: AssetManager): LoadResult<StarCatalog, AstroCatalogStats> {
            val astroResult = loadAstroCatalog(assetManager)
            val catalog: StarCatalog = AstroStarCatalogAdapter(astroResult.catalog)
            return LoadResult(
                catalog = catalog,
                metadata = astroResult.metadata,
                loadDurationMs = astroResult.loadDurationMs,
            )
        }

        private fun loadAstroCatalog(assetManager: AssetManager): LoadResult<AstroCatalog, AstroCatalogStats> {
            var metadata: AstroCatalogStats? = null
            val catalog: AstroCatalog
            val durationNs = measureNanoTime {
                val loaded = runBlocking {
                    PtskCatalogLoader(assetManager).load()
                } ?: EmptyAstroCatalog
                catalog = loaded
                metadata = buildAstroMetadata(loaded)
            }
            return LoadResult(
                catalog = catalog,
                metadata = metadata,
                loadDurationMs = (durationNs / 1_000_000.0).roundToInt().toLong(),
            )
        }

        private fun buildAstroMetadata(catalog: AstroCatalog): AstroCatalogStats? {
            if (catalog === EmptyAstroCatalog) return null
            val constellations = runCatching {
                (0..87).map { index -> catalog.getConstellationMeta(ConstellationId(index)) }.distinctBy { it.id }
            }.getOrDefault(emptyList())
            val asterismCount = runCatching {
                constellations.sumOf { meta -> catalog.asterismsByConstellation(meta.id).size }
            }.getOrDefault(0)
            val artOverlayCount = runCatching {
                constellations.sumOf { meta -> catalog.artOverlaysByConstellation(meta.id).size }
            }.getOrDefault(0)
            return AstroCatalogStats(
                starCount = catalog.allStars().size,
                constellationCount = constellations.size,
                asterismCount = asterismCount,
                artOverlayCount = artOverlayCount,
            )
        }

        private fun loadBoundaries(provider: AssetProvider): LoadResult<ConstellationBoundaries, BinaryConstellationBoundaries.Metadata> {
            var metadata: BinaryConstellationBoundaries.Metadata? = null
            val boundaries: ConstellationBoundaries
            val durationNs = measureNanoTime {
                val loaded = BinaryConstellationBoundaries.load(provider)
                boundaries = loaded
                metadata = (loaded as? BinaryConstellationBoundaries)?.metadata
            }
            return LoadResult(
                catalog = boundaries,
                metadata = metadata,
                loadDurationMs = (durationNs / 1_000_000.0).roundToInt().toLong(),
            )
        }
    }

    private data class LoadResult<T, M>(
        val catalog: T,
        val metadata: M?,
        val loadDurationMs: Long,
    )
}

data class CatalogDiagnostics(
    val starMetadata: AstroCatalogStats?,
    val starLoadDurationMs: Long,
    val boundaryMetadata: BinaryConstellationBoundaries.Metadata?,
    val boundaryLoadDurationMs: Long,
    val usingBinaryBoundaries: Boolean,
)

data class AstroCatalogStats(
    val starCount: Int,
    val constellationCount: Int,
    val asterismCount: Int,
    val artOverlayCount: Int,
)
