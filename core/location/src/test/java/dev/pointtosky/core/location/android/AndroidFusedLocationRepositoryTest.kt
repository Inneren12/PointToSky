package dev.pointtosky.core.location.android

import android.location.Location
import android.location.LocationManager
import dev.pointtosky.core.location.model.ProviderType
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidFusedLocationRepositoryTest {

    @Test
    fun `toLocationFixInternal maps provider and normalizes values`() {
        val location = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 95.0
            longitude = 190.0
            time = 1_000L
            accuracy = 5.5f
            altitude = 400.0
            bearing = 45.0f
            speed = 3.5f
        }

        val fix = location.toLocationFixInternal()

        assertNotNull(fix)
        assertEquals(ProviderType.GPS, fix.provider)
        assertEquals(90.0, fix.point.latDeg)
        assertEquals(-170.0, fix.point.lonDeg)
        assertEquals(5.5f, fix.accuracyM)
        assertEquals(400.0, fix.altitudeM)
        assertEquals(45.0f, fix.bearingDeg)
        assertEquals(3.5f, fix.speedMps)
    }

    @Test
    fun `isFresh respects ttl boundaries`() {
        val location = Location("fused").apply { time = 10_000L }

        assertTrue(location.isFresh(nowMs = 10_500L, ttlMs = 1_000L))
        assertFalse(location.isFresh(nowMs = 12_001L, ttlMs = 1_000L))
        assertFalse(location.isFresh(nowMs = 9_000L, ttlMs = 1_000L))
    }

    @Test
    fun `applyThrottle emits latest sample`() = runTest {
        val upstream = MutableSharedFlow<Int>(extraBufferCapacity = 4)
        val collected = mutableListOf<Int>()
        val job = launch { upstream.applyThrottle(1_000L).toList(collected) }

        upstream.emit(1)
        upstream.emit(2)
        advanceTimeBy(1_000L)
        upstream.emit(3)
        advanceTimeBy(1_000L)

        job.cancel()

        assertEquals(listOf(2, 3), collected)
    }
}
