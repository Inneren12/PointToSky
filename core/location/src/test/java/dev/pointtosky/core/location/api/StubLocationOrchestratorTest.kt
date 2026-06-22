package dev.pointtosky.core.location.api

import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.location.model.ProviderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class StubLocationOrchestratorTest {

    private class FakeDelegateRepo(private val lastKnown: LocationFix?) : LocationRepository {
        private val _fixes = MutableSharedFlow<LocationFix>()
        override val fixes: Flow<LocationFix> = _fixes
        override suspend fun start(config: LocationConfig) = Unit
        override suspend fun stop() = Unit
        override suspend fun getLastKnown(): LocationFix? = lastKnown
    }

    @Test
    fun `currentFix seeds from delegate getLastKnown when delegate is quiet and manual is null`() = runTest {
        val cachedFix = LocationFix(
            point = GeoPoint(10.0, 20.0),
            timeMs = 1000L,
            accuracyM = 8f,
            provider = ProviderType.FUSED,
        )
        val stub = StubLocationOrchestrator(FakeDelegateRepo(lastKnown = cachedFix))

        val received = mutableListOf<LocationFix?>()
        val job = launch { stub.currentFix.collect { received += it } }
        advanceUntilIdle()

        val emitted = received.filterNotNull().firstOrNull()
        assertNotNull(emitted, "currentFix should emit the delegate's cached fix when delegate is quiet")
        assertEquals(cachedFix, emitted)

        job.cancel()
    }
}
