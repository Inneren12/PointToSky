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

    private class FakeFusedRepository(
        private val lastKnownResult: LocationFix? = null,
    ) : LocationRepository {
        private val _fixes = MutableSharedFlow<LocationFix>(extraBufferCapacity = 8)
        override val fixes: Flow<LocationFix> = _fixes
        override suspend fun start(config: LocationConfig) = Unit
        override suspend fun stop() = Unit
        override suspend fun getLastKnown(): LocationFix? = lastKnownResult
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
    fun `remote getLastKnown fresh fix seeds availability at startup`() = runTest {
        var now = 0L
        val prefs = FakeLocationPrefs()
        // fakeRemote has a cached fix but emits nothing live
        val fakeRemote = FakeFusedRepository(lastKnownResult = remoteFix(timeMs = 0L))
        val orchestrator = DefaultLocationOrchestrator(
            fused = null, manualPrefs = prefs, remotePhone = fakeRemote,
            clock = { now }, manualReemitIntervalMs = -1L,
            freshTtlMs = 1_000L, remoteFreshnessTickMs = 100L,
        )

        // Caller fetches last-known before collecting currentFix; this seeds latestFix with the
        // REMOTE_PHONE fix. No live remote emission will arrive.
        prefs.setUsePhoneFallback(true)
        orchestrator.getLastKnown()

        val received = mutableListOf<LocationFix?>()
        val job = launch { orchestrator.currentFix.collect { received += it } }
        pump()
        // remoteFresh: latestRemoteFix=null, latestFix=REMOTE_PHONE(timeMs=0), 0-0=0≤1000 → true
        // anyProviderAvailable = useFallback && remoteOk = true && true = true
        // currentFix emits the cached fix (seeded via fixes.onStart { latestFix.get() })

        assertNotNull(received.last(), "cached REMOTE_PHONE fix from getLastKnown must make currentFix non-null at startup")

        job.cancel()
    }

    @Test
    fun `remote getLastKnown fix ages out after freshTtlMs with no live emission`() = runTest {
        var now = 0L
        val prefs = FakeLocationPrefs()
        val fakeRemote = FakeFusedRepository(lastKnownResult = remoteFix(timeMs = 0L))
        val orchestrator = DefaultLocationOrchestrator(
            fused = null, manualPrefs = prefs, remotePhone = fakeRemote,
            clock = { now }, manualReemitIntervalMs = -1L,
            freshTtlMs = 1_000L, remoteFreshnessTickMs = 100L,
        )

        prefs.setUsePhoneFallback(true)
        orchestrator.getLastKnown()

        val received = mutableListOf<LocationFix?>()
        val job = launch { orchestrator.currentFix.collect { received += it } }
        pump()
        assertNotNull(received.last(), "should be non-null with a fresh cached remote fix")

        // Advance clock past freshTtlMs; no live remote fix arrives to refresh latestRemoteFix.
        now = 2_000L
        pump(2_000L)
        // ticker: clock()-rf.timeMs = 2000-0 = 2000 > 1000 → false → anyProviderAvailable=false

        assertNull(received.last(), "currentFix must become null once the cached remote fix ages past freshTtlMs")

        job.cancel()
    }

    @Test
    fun `start(config) freshTtlMs overrides constructor default for remote freshness`() = runTest {
        var now = 0L
        val prefs = FakeLocationPrefs()
        val fakeRemote = FakeFusedRepository()
        // Constructor freshTtlMs defaults to LocationConfig().freshTtlMs (120_000ms).
        // start() must override it; otherwise the fix would still look fresh well past 1_000ms.
        val orchestrator = DefaultLocationOrchestrator(
            fused = null, manualPrefs = prefs, remotePhone = fakeRemote,
            clock = { now }, manualReemitIntervalMs = -1L,
            remoteFreshnessTickMs = 100L,
        )

        orchestrator.start(LocationConfig(freshTtlMs = 1_000L))  // activeFreshTtlMs = 1_000
        prefs.setUsePhoneFallback(true)

        val received = mutableListOf<LocationFix?>()
        val job = launch { orchestrator.currentFix.collect { received += it } }
        pump()

        fakeRemote.emit(remoteFix(timeMs = now))
        pump()          // latestRemoteFix updated
        pump(100L)      // ticker fires: 0-0=0 ≤ 1_000 → true → non-null
        assertNotNull(received.last(), "remote fix should be available while within the config TTL")

        // Advance past the config TTL (1_000ms); the constructor default of 120_000ms must not apply.
        now = 2_000L
        pump(2_000L)    // ticker: 2000-0=2000 > 1000 → false → null

        assertNull(received.last(), "currentFix must use start(config).freshTtlMs (1_000ms), not the constructor default")

        job.cancel()
    }

    @Test
    fun `start(config) larger freshTtlMs keeps remote fix available longer than constructor default`() = runTest {
        var now = 0L
        val prefs = FakeLocationPrefs()
        val fakeRemote = FakeFusedRepository()
        val orchestrator = DefaultLocationOrchestrator(
            fused = null, manualPrefs = prefs, remotePhone = fakeRemote,
            clock = { now }, manualReemitIntervalMs = -1L,
            freshTtlMs = 500L,          // small constructor default
            remoteFreshnessTickMs = 100L,
        )

        orchestrator.start(LocationConfig(freshTtlMs = 3_000L))  // activeFreshTtlMs = 3_000
        prefs.setUsePhoneFallback(true)

        val received = mutableListOf<LocationFix?>()
        val job = launch { orchestrator.currentFix.collect { received += it } }
        pump()

        fakeRemote.emit(remoteFix(timeMs = now))
        pump()
        pump(100L)
        assertNotNull(received.last(), "remote fix should be available initially")

        // Advance past the constructor default (500ms) but not the config TTL (3_000ms).
        now = 1_000L
        pump(1_000L)    // ticker: 1000-0=1000 ≤ 3000 → still true

        assertNotNull(received.last(), "remote fix must remain available; start(config) TTL of 3_000ms has not elapsed")

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
