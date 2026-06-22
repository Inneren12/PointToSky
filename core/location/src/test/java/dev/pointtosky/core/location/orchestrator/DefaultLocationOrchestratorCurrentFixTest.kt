package dev.pointtosky.core.location.orchestrator

import dev.pointtosky.core.location.api.LocationConfig
import dev.pointtosky.core.location.api.LocationRepository
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.location.model.ProviderType
import dev.pointtosky.core.location.prefs.LocationPrefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DefaultLocationOrchestratorCurrentFixTest {

    // ---- Fakes ----

    private class FakeLocationPrefs : LocationPrefs {
        private val _manual = MutableStateFlow<GeoPoint?>(null)
        private val _fallback = MutableStateFlow(false)
        private val _share = MutableStateFlow(false)

        override val manualPointFlow: Flow<GeoPoint?> = _manual
        override val usePhoneFallbackFlow: Flow<Boolean> = _fallback
        override val shareLocationWithWatchFlow: Flow<Boolean> = _share

        override suspend fun setManual(point: GeoPoint?) { _manual.value = point }
        override suspend fun setUsePhoneFallback(enabled: Boolean) { _fallback.value = enabled }
        override suspend fun setShareLocationWithWatch(share: Boolean) { _share.value = share }
    }

    private class FakeFusedRepository : LocationRepository {
        private val _fixes = MutableSharedFlow<LocationFix>(extraBufferCapacity = 8)
        override val fixes: Flow<LocationFix> = _fixes
        override suspend fun start(config: LocationConfig) = Unit
        override suspend fun stop() = Unit
        override suspend fun getLastKnown(): LocationFix? = null
        suspend fun emit(fix: LocationFix) = _fixes.emit(fix)
    }

    private fun manualFix(lat: Double = 45.0, lon: Double = -113.0) = LocationFix(
        point = GeoPoint(lat, lon),
        timeMs = 0,
        accuracyM = 0f,
        provider = ProviderType.MANUAL,
    )

    private fun gpsFix(lat: Double = 45.0, lon: Double = -113.0) = LocationFix(
        point = GeoPoint(lat, lon),
        timeMs = 0,
        accuracyM = 10f,
        provider = ProviderType.GPS,
    )

    private fun remoteFix(lat: Double = 45.0, lon: Double = -113.0, timeMs: Long = 0L) = LocationFix(
        point = GeoPoint(lat, lon),
        timeMs = timeMs,
        accuracyM = 5f,
        provider = ProviderType.REMOTE_PHONE,
    )

    // ---- Helpers ----

    // Cannot use advanceUntilIdle() while a currentFix collector is active: the remoteFresh
    // ticker is an infinite delayed loop and will never let the scheduler become idle.
    // pump() is the bounded alternative: optionally advance virtual time, then drain pending work.
    private fun TestScope.pump(ms: Long = 0) {
        if (ms > 0) advanceTimeBy(ms)
        runCurrent()
    }

    // ---- Tests ----

    @Test
    fun `currentFix emits a fix once a fix arrives`() = runTest {
        val prefs = FakeLocationPrefs()
        val orchestrator = DefaultLocationOrchestrator(
            fused = null,
            manualPrefs = prefs,
            remotePhone = null,
            clock = { 0L },
            manualReemitIntervalMs = -1L,
        )

        val received = mutableListOf<LocationFix?>()
        val job = launch { orchestrator.currentFix.collect { received += it } }
        pump()  // let the flow start; remoteFresh emits first false; combine initialises → null

        prefs.setManual(GeoPoint(45.0, -113.0))
        pump()  // manualPointFlow fires → anyProviderAvailable=true; manualFixFlow emits

        assertTrue(received.any { it != null }, "currentFix should emit a non-null fix once a source provides one")
        val fix = received.filterNotNull().first()
        assertEquals(ProviderType.MANUAL, fix.provider)

        job.cancel()
    }

    @Test
    fun `currentFix does not emit null when provider is quiet`() = runTest {
        val prefs = FakeLocationPrefs()
        val orchestrator = DefaultLocationOrchestrator(
            fused = null,
            manualPrefs = prefs,
            remotePhone = null,
            clock = { 0L },
            manualReemitIntervalMs = -1L,  // emit once only; quiet after that
        )

        val received = mutableListOf<LocationFix?>()
        val job = launch { orchestrator.currentFix.collect { received += it } }
        pump()

        // Fix arrives exactly once; provider goes quiet (re-emit disabled).
        prefs.setManual(GeoPoint(45.0, -113.0))
        pump()

        val fixCount = received.count { it != null }
        assertTrue(fixCount > 0, "A fix must have been emitted")

        // No new emissions. Last value must remain the fix, not null.
        val lastValue = received.last()
        assertNotNull(lastValue, "currentFix must not become null when the provider is merely quiet")

        job.cancel()
    }

    @Test
    fun `currentFix emits null when the only source becomes unavailable`() = runTest {
        val prefs = FakeLocationPrefs()
        val orchestrator = DefaultLocationOrchestrator(
            fused = null,
            manualPrefs = prefs,
            remotePhone = null,
            clock = { 0L },
            manualReemitIntervalMs = -1L,
        )

        val received = mutableListOf<LocationFix?>()
        val job = launch { orchestrator.currentFix.collect { received += it } }
        pump()

        prefs.setManual(GeoPoint(45.0, -113.0))
        pump()
        assertTrue(received.any { it != null }, "Should have a fix before clearing")

        // Remove the only source.
        prefs.setManual(null)
        pump()

        assertNull(received.last(), "currentFix must emit null when all location sources are gone")

        job.cancel()
    }

    @Test
    fun `currentFix recovers when manual source is re-enabled after being cleared`() = runTest {
        val prefs = FakeLocationPrefs()
        val orchestrator = DefaultLocationOrchestrator(
            fused = null,
            manualPrefs = prefs,
            remotePhone = null,
            clock = { 0L },
            manualReemitIntervalMs = -1L,
        )

        val received = mutableListOf<LocationFix?>()
        val job = launch { orchestrator.currentFix.collect { received += it } }
        pump()

        // Step 1: fix arrives.
        prefs.setManual(GeoPoint(45.0, -113.0))
        pump()
        val firstFix = received.filterNotNull().lastOrNull()
        assertNotNull(firstFix, "Should have received a fix")

        // Step 2: source goes away → null.
        prefs.setManual(null)
        pump()
        assertNull(received.last(), "Should be null after manual source is cleared")

        // Step 3: re-enable manual → anyProviderAvailable becomes true; manualFixFlow emits.
        prefs.setManual(GeoPoint(45.0, -113.0))
        pump()

        val restored = received.last()
        assertNotNull(restored, "currentFix should be non-null when manual source is re-enabled")
        assertEquals(firstFix.point, restored.point)

        job.cancel()
    }

    @Test
    fun `remote goes stale - currentFix emits null (fallback on, no fused or manual)`() = runTest {
        var now = 0L
        val prefs = FakeLocationPrefs()
        val fakeRemote = FakeFusedRepository()
        val orchestrator = DefaultLocationOrchestrator(
            fused = null, manualPrefs = prefs, remotePhone = fakeRemote,
            clock = { now }, manualReemitIntervalMs = -1L,
            freshTtlMs = 1_000L, remoteFreshnessTickMs = 100L,
        )

        val received = mutableListOf<LocationFix?>()
        val job = launch { orchestrator.currentFix.collect { received += it } }
        pump()  // remoteFresh emits false; combine initialises → null

        prefs.setUsePhoneFallback(true)
        pump()  // anyProviderAvailable still false (remoteOk=false)

        // Emit a fresh remote fix; latestRemoteFix is updated but the ticker hasn't re-fired yet.
        fakeRemote.emit(remoteFix(timeMs = now))
        pump()

        // Advance past the next tick so remoteFresh re-evaluates: now=0, rf.timeMs=0 → fresh.
        pump(100L)
        assertTrue(received.any { it != null }, "Should have received the remote fix while fresh")

        // Age the clock past freshTtlMs and let the ticker fire — remote fix is now stale.
        now += 2_000L
        pump(2_000L)

        assertNull(received.last(), "currentFix must emit null when the remote fix goes stale")

        job.cancel()
    }

    @Test
    fun `remote keeps emitting fresh fixes - currentFix never nulls (connected stationary phone)`() = runTest {
        var now = 0L
        val prefs = FakeLocationPrefs()
        val fakeRemote = FakeFusedRepository()
        val orchestrator = DefaultLocationOrchestrator(
            fused = null, manualPrefs = prefs, remotePhone = fakeRemote,
            clock = { now }, manualReemitIntervalMs = -1L,
            freshTtlMs = 1_000L, remoteFreshnessTickMs = 100L,
        )

        val received = mutableListOf<LocationFix?>()
        val job = launch { orchestrator.currentFix.collect { received += it } }
        pump()  // remoteFresh emits false

        prefs.setUsePhoneFallback(true)
        pump()

        // Each iteration mimics the phone re-fetching a fresh-timestamped location while stationary.
        // Advance clock and emit before each tick so remoteFresh always sees a fresh fix.
        repeat(5) {
            now += 500L
            fakeRemote.emit(remoteFix(timeMs = now))
            pump(100L)  // let the ticker fire; clock()=now, rf.timeMs=now → always fresh
        }

        assertTrue(received.filterNotNull().isNotEmpty(), "Should have received remote fixes")
        assertNotNull(received.last(), "currentFix must never become null while the phone keeps sending fresh fixes")

        job.cancel()
    }

    @Test
    fun `fallback enabled but no remote source - currentFix stays null without a real fix`() = runTest {
        val prefs = FakeLocationPrefs()
        val orchestrator = DefaultLocationOrchestrator(
            fused = null, manualPrefs = prefs, remotePhone = null,
            clock = { 0L }, manualReemitIntervalMs = -1L,
            freshTtlMs = 1_000L, remoteFreshnessTickMs = 100L,
        )

        val received = mutableListOf<LocationFix?>()
        val job = launch { orchestrator.currentFix.collect { received += it } }
        pump()  // remoteFresh emits false (latestRemoteFix is null and remotePhone is null)

        prefs.setUsePhoneFallback(true)
        pump()  // anyProviderAvailable = useFallback && remoteOk = true && false = false

        assertNull(received.last(), "the usePhoneFallback preference alone must not fabricate availability; currentFix must remain null")

        job.cancel()
    }

    @Test
    fun `currentFix emits fused fix when fused repository provides one`() = runTest {
        val prefs = FakeLocationPrefs()
        val fused = FakeFusedRepository()
        val orchestrator = DefaultLocationOrchestrator(
            fused = fused,
            manualPrefs = prefs,
            remotePhone = null,
            clock = { 0L },
        )

        val received = mutableListOf<LocationFix?>()
        val job = launch { orchestrator.currentFix.collect { received += it } }
        pump()  // remoteFresh emits false; combine initialises

        val fix = gpsFix()
        fused.emit(fix)
        pump()  // fusedAvailable flips true → anyProviderAvailable=true → currentFix=fix

        assertTrue(received.any { it != null }, "currentFix should emit the fused fix")
        assertEquals(ProviderType.GPS, received.filterNotNull().last().provider)

        job.cancel()
    }
}
