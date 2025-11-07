package dev.pointtosky.wear.tile.tonight

import dev.pointtosky.core.time.TimeSource
import dev.pointtosky.core.time.ZoneRepo
import dev.pointtosky.core.astro.ephem.SimpleEphemerisComputer
import dev.pointtosky.core.catalog.star.FakeStarCatalog
import dev.pointtosky.core.location.model.GeoPoint
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

private class FixedTimeSource(private val instant: Instant) : TimeSource {
    override fun now(): Instant = instant
    override val ticks = kotlinx.coroutines.flow.flow { emit(instant) }
}

private class FixedZoneRepo(private val zone: ZoneId) : ZoneRepo(null as android.content.Context?) {
    override fun current(): ZoneId = zone
}

class RealTonightProviderTest {

    @Test
    fun `planet and star present in top`() = runBlocking {
        val now = Instant.parse("2025-06-15T21:00:00Z")
        val provider = RealTonightProvider(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            timeSource = FixedTimeSource(now),
            zoneRepo = ZoneRepo(androidx.test.core.app.ApplicationProvider.getApplicationContext()),
            ephemeris = SimpleEphemerisComputer(),
            starCatalog = FakeStarCatalog(),
            getLastKnownLocation = { GeoPoint(lat = 52.52, lon = 13.40) } // Berlin
        )
        val model = provider.getModel(now)
        assertTrue(model.items.isNotEmpty())
        assertTrue(model.items.any { it.icon != TonightIcon.STAR }) // есть планета
        assertTrue(model.items.any { it.icon == TonightIcon.STAR }) // есть звезда
        assertTrue(model.items.size <= 3)
    }

    @Test
    fun `no location fallback`() = runBlocking {
        val now = Instant.parse("2025-06-15T21:00:00Z")
        val provider = RealTonightProvider(
            context = androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            timeSource = FixedTimeSource(now),
            zoneRepo = ZoneRepo(androidx.test.core.app.ApplicationProvider.getApplicationContext()),
            ephemeris = SimpleEphemerisComputer(),
            starCatalog = FakeStarCatalog(),
            getLastKnownLocation = { null }
        )
        val model = provider.getModel(now)
        // фолбэк как минимум Moon + Vega
        assertTrue(model.items.any { it.title.contains("Moon", ignoreCase = true) })
        assertTrue(model.items.any { it.title.contains("Vega", ignoreCase = true) || it.icon == TonightIcon.STAR })
    }
}
