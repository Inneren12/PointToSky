package dev.pointtosky.wear.tile.tonight

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.identify.angularSeparationDeg
import dev.pointtosky.core.catalog.star.Star
import dev.pointtosky.core.catalog.star.StarCatalog
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.time.ZoneRepo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import java.util.TimeZone

/**
 * Юнит‑тесты эвристики TonightProvider.
 * Основано на текущей реализации RealTonightProvider (S7.C–S7.F). :contentReference[oaicite:1]{index=1}
 */
@RunWith(RobolectricTestRunner::class)
class RealTonightProviderTest {
    private lateinit var app: Context
    private lateinit var zoneRepo: ZoneRepo

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Фиксируем системную зону (ZoneRepo обычно её и возвращает)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        zoneRepo = ZoneRepo(app)
    }

    private fun newProvider(getLoc: suspend () -> GeoPoint?) =
        RealTonightProvider(
            context = app,
            zoneRepo = zoneRepo,
            // Критично: подменяем каталог на тестовый, чтобы не грузить assets/bin
            starCatalog = StubStarCatalog(),
            getLastKnownLocation = getLoc,
        )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun fallbackNoLocation_returnsMoonAndVega() =
        runTest {
            val p = newProvider { null } // нет локации → Moon + Vega
            val model = p.getModel(Instant.parse("2025-01-01T12:00:00Z"))
            assertThat(model.items.size).isAtLeast(2)
            val ids = model.items.map { it.id }.toSet()
            assertThat(ids).contains("MOON")
            assertThat(ids).contains("VEGA")
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun withLocation_produces2to3Items() =
        runTest {
            val gp = GeoPoint(latDeg = 55.75, lonDeg = 37.62) // Москва
            val p = newProvider { gp }
            val model = p.getModel(Instant.parse("2025-01-10T18:00:00Z"))
            assertThat(model.items.size).isAtLeast(2)
            assertThat(model.items.size).isAtMost(3)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun planetsHavePriority_ifPresent() =
        runTest {
            val gp = GeoPoint(latDeg = 55.75, lonDeg = 37.62)
            val p = newProvider { gp }
            val model = p.getModel(Instant.parse("2025-02-01T18:00:00Z"))
            val first = model.items.firstOrNull()
            // В текущей реализации планеты имеют приоритет KIND.PLANET > KIND.STAR. :contentReference[oaicite:2]{index=2}
            // Если среди топ‑элементов есть планета — она должна быть выше любой звезды.
            val planetIdx =
                model.items.indexOfFirst {
                    it.icon == TonightIcon.MOON ||
                        it.icon == TonightIcon.JUPITER ||
                        it.icon == TonightIcon.SATURN
                }
            if (planetIdx >= 0 && first != null) {
                assertThat(planetIdx).isEqualTo(0)
            }
        }
}

private class StubStarCatalog : StarCatalog {
    private data class Candidate(val star: Star, val separationDeg: Double)

    private val stars: List<Star> = listOf(
        Star(1, 101.287f, -16.716f, -1.46f, "Sirius", "α CMa", "9 CMa", "CMA"),
        Star(5, 279.234f, 38.783f, 0.03f, "Vega", "α Lyr", "3 Lyr", "LYR"),
        Star(12, 297.695f, 8.868f, 0.77f, "Altair", "α Aql", "53 Aql", "AQL"),
        Star(18, 310.358f, 45.280f, 1.25f, "Deneb", "α Cyg", "50 Cyg", "CYG"),
    )

    override fun nearby(center: Equatorial, radiusDeg: Double, magLimit: Double?): List<Star> {
        return stars.asSequence()
            .map { star ->
                val separation = angularSeparationDeg(center, Equatorial(star.raDeg.toDouble(), star.decDeg.toDouble()))
                Candidate(star, separation)
            }
            .filter { candidate ->
                candidate.separationDeg <= radiusDeg &&
                    (magLimit == null || candidate.star.mag.toDouble() <= magLimit)
            }
            .sortedBy { it.separationDeg }
            .map { it.star }
            .toList()
    }
}
