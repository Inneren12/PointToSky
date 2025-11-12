package dev.pointtosky.wear.tile.tonight

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import dev.pointtosky.core.catalog.star.FakeStarCatalog
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
            starCatalog = FakeStarCatalog(),
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
