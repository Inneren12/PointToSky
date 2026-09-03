package dev.pointtosky.core.location.android

import android.location.Location
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.LocationRequest
import dev.pointtosky.core.location.api.LocationConfig
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.location.model.ProviderType
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
class AndroidFusedLocationRepositoryTest {

    @Test
    fun `location mapping converts provider and fields`() {
        val location = Location("gps").apply {
            latitude = 10.0
            longitude = 20.0
            time = 1234L
            accuracy = 5.5f
            altitude = 100.0
            bearing = 90f
            speed = 2.5f
        }

        val fix = location.toLocationFix()

        assertEquals(GeoPoint(10.0, 20.0), fix.point)
        assertEquals(1234L, fix.timeMs)
        assertEquals(5.5f, fix.accuracyM)
        assertEquals(ProviderType.GPS, fix.provider)
        assertEquals(100.0, fix.altitudeM)
        assertEquals(90f, fix.bearingDeg)
        assertEquals(2.5f, fix.speedMps)
    }

    @Test
    fun `emits fresh last known fix on start`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val delegate = FakeFusedClientDelegate()
        var now = 200_000L
        val repo = AndroidFusedLocationRepository(
            context = ApplicationProvider.getApplicationContext(),
            io = dispatcher,
            timeProvider = { now },
            delegate = delegate,
        )

        val ttl = 120_000L
        delegate.lastLocation = Location("fused").apply {
            latitude = 1.0
            longitude = 2.0
            time = now - ttl / 2
            accuracy = 15f
        }

        val config = LocationConfig(throttleMs = 5_000L, freshTtlMs = ttl)
        val result = asyncCollectFirst(repo)

        repo.start(config)
        advanceUntilIdle()

        val fix = result.await()
        assertNotNull(fix)
        assertEquals(GeoPoint(1.0, 2.0), fix.point)
        assertEquals(ProviderType.FUSED, fix.provider)

        // repo.start() launches a collector on its own internal scope that never completes on
        // its own; without stopping it, runTest sees an uncompleted coroutine on the shared test
        // dispatcher at the end of the test body and fails with UncompletedCoroutinesError.
        repo.stop()
        advanceUntilIdle()
    }

    @Test
    fun `throttles emitted updates`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val delegate = FakeFusedClientDelegate()
        val repo = AndroidFusedLocationRepository(
            context = ApplicationProvider.getApplicationContext(),
            io = dispatcher,
            delegate = delegate,
        )

        val config = LocationConfig(throttleMs = 1_000L)
        val collected = mutableListOf<LocationFixHolder>()
        val collectionJob = launch {
            repo.fixes.collect {
                collected += LocationFixHolder(it.point.latDeg, it.point.lonDeg, it.timeMs)
            }
        }

        repo.start(config)
        advanceUntilIdle()

        delegate.emit(LocationFixHolder(0.0, 0.0, 0L))
        advanceUntilIdle()

        delegate.emit(LocationFixHolder(1.0, 1.0, 100L))
        advanceTimeBy(999)
        advanceUntilIdle()

        delegate.emit(LocationFixHolder(2.0, 2.0, 200L))
        advanceTimeBy(1)
        advanceUntilIdle()

        delegate.emit(LocationFixHolder(3.0, 3.0, 1200L))
        advanceTimeBy(1_000)
        advanceUntilIdle()

        collectionJob.cancel()
        repo.stop()

        assertEquals(3, collected.size)
        assertEquals(LocationFixHolder(0.0, 0.0, 0L), collected[0])
        assertEquals(LocationFixHolder(2.0, 2.0, 200L), collected[1])
        assertEquals(LocationFixHolder(3.0, 3.0, 1200L), collected[2])
    }

    private fun TestScope.asyncCollectFirst(repo: AndroidFusedLocationRepository) = async {
        repo.fixes.first()
    }

    private class FakeFusedClientDelegate : AndroidFusedLocationRepository.FusedClientDelegate {
        private val updates = MutableSharedFlow<LocationFixHolder>()
        var lastLocation: Location? = null
        var currentLocationResult: Location? = null

        override fun locationUpdates(request: LocationRequest): Flow<LocationFix> {
            return updates.mapToFixes()
        }

        override suspend fun lastLocation(): Location? = lastLocation

        override suspend fun currentLocation(timeoutMs: Long, priority: Int): Location? = currentLocationResult

        suspend fun emit(holder: LocationFixHolder) {
            updates.emit(holder)
        }
    }

    // internal (not private): the top-level mapToFixes() below needs to reference this type from
    // outside the class body.
    internal data class LocationFixHolder(val lat: Double, val lon: Double, val time: Long)
}

// Top-level (not a member of AndroidFusedLocationRepositoryTest): FakeFusedClientDelegate is a
// plain nested class, not an inner class, so it has no implicit outer-instance receiver and
// can't see member extension functions declared on the test class.
private fun Flow<AndroidFusedLocationRepositoryTest.LocationFixHolder>.mapToFixes(): Flow<LocationFix> = this.map { holder ->
    LocationFix(
        point = GeoPoint(holder.lat, holder.lon),
        timeMs = holder.time,
        accuracyM = null,
        provider = ProviderType.FUSED,
    )
}
