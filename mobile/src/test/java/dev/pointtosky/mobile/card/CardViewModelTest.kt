package dev.pointtosky.mobile.card

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.Ephemeris
import dev.pointtosky.core.astro.ephem.EphemerisComputer
import dev.pointtosky.core.astro.visibility.Bortle
import dev.pointtosky.core.astro.visibility.LightPollutionGrid
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.mobile.visibility.BortleSource
import dev.pointtosky.mobile.visibility.VisibilitySettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalCoroutinesApi::class)
class CardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() {
        CardRepository.resetForTests()
        VisibilitySettings.reset()
    }

    @After
    fun tearDown() {
        CardRepository.resetForTests()
        VisibilitySettings.reset()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeVm(
        cardId: String,
        locationPrefs: LocationPrefs,
        deviceFlow: StateFlow<GeoPoint?>,
        ephemerisComputer: EphemerisComputer = FakeEphemerisComputer(),
        lightPollutionGrid: StateFlow<LightPollutionGrid?> = MutableStateFlow(null),
    ): CardViewModel =
        CardViewModel(
            cardId = cardId,
            repository = CardRepository,
            locationPrefs = locationPrefs,
            deviceLocationFlow = deviceFlow,
            clock = { FIXED_INSTANT },
            ephemerisComputer = ephemerisComputer,
            lightPollutionGrid = lightPollutionGrid,
        )

    private fun starModel(
        id: String,
        equatorial: Equatorial,
        magnitude: Double = 1.0,
    ): CardObjectModel =
        CardObjectModel(
            id = id,
            type = CardObjectType.STAR,
            name = id,
            body = null,
            constellation = "TST",
            magnitude = magnitude,
            equatorial = equatorial,
            horizontal = null,
            bestWindow = null,
        )

    private fun constModel(id: String): CardObjectModel =
        CardObjectModel(
            id = id,
            type = CardObjectType.CONST,
            name = id,
            body = null,
            constellation = id,
            magnitude = null,
            equatorial = null,
            horizontal = null,
            bestWindow = null,
        )

    // -------------------------------------------------------------------------
    // Tests — location resolution (unchanged)
    // -------------------------------------------------------------------------

    @Test
    fun `manual location wins over device location`() {
        val devicePoint = GeoPoint(latDeg = 10.0, lonDeg = 10.0)
        val manualPoint = GeoPoint(latDeg = 50.0, lonDeg = 14.0)
        val equatorial = Equatorial(raDeg = 101.29, decDeg = -16.72)
        CardRepository.update("star1", CardRepository.Entry.Ready(starModel("star1", equatorial)))

        // ViewModel with both device and manual set: manual must win
        val vmBoth = makeVm(
            "star1",
            FakeLocationPrefs(MutableStateFlow(manualPoint)),
            MutableStateFlow(devicePoint),
        )
        // ViewModel with only manual set (device = null): should produce identical horizontal
        val vmManualOnly = makeVm(
            "star1",
            FakeLocationPrefs(MutableStateFlow(manualPoint)),
            MutableStateFlow(null),
        )

        val stateBoth = vmBoth.state.value as CardUiState.Ready
        val stateManualOnly = vmManualOnly.state.value as CardUiState.Ready

        // If manual had taken priority, the horizontal coords are the same regardless of device
        assertEquals(stateManualOnly.horizontal, stateBoth.horizontal)
    }

    @Test
    fun `device location is used as fallback when manual point is null`() {
        val devicePoint = GeoPoint(latDeg = 51.5, lonDeg = -0.1)
        val equatorial = Equatorial(raDeg = 101.29, decDeg = -16.72)
        CardRepository.update("star2", CardRepository.Entry.Ready(starModel("star2", equatorial)))

        val vm = makeVm(
            "star2",
            FakeLocationPrefs(MutableStateFlow(null)),
            MutableStateFlow(devicePoint),
        )

        val state = vm.state.value as CardUiState.Ready
        // Device point present → horizontal must be computed
        assertNotNull(state.horizontal)
    }

    @Test
    fun `null manual and null device leaves horizontal null but still emits Ready`() {
        val equatorial = Equatorial(raDeg = 101.29, decDeg = -16.72)
        CardRepository.update("star3", CardRepository.Entry.Ready(starModel("star3", equatorial)))

        val vm = makeVm(
            "star3",
            FakeLocationPrefs(MutableStateFlow(null)),
            MutableStateFlow(null),
        )

        val state = vm.state.value
        assertTrue(state is CardUiState.Ready)
        // No resolved location → Alt/Az must be absent, not a wrong default
        assertNull((state as CardUiState.Ready).horizontal)
    }

    @Test
    fun `updating device flow while manual is null updates horizontal reactively`() {
        val equatorial = Equatorial(raDeg = 101.29, decDeg = -16.72)
        CardRepository.update("star4", CardRepository.Entry.Ready(starModel("star4", equatorial)))

        val deviceFlow = MutableStateFlow<GeoPoint?>(null)
        val vm = makeVm(
            "star4",
            FakeLocationPrefs(MutableStateFlow(null)),
            deviceFlow,
        )

        assertNull((vm.state.value as CardUiState.Ready).horizontal)

        deviceFlow.value = GeoPoint(latDeg = 51.5, lonDeg = -0.1)

        assertNotNull((vm.state.value as CardUiState.Ready).horizontal)
    }

    // -------------------------------------------------------------------------
    // Tests — belowVisibilityLimit / limitingMag
    // -------------------------------------------------------------------------

    @Test
    fun `faint star below limiting magnitude flags belowVisibilityLimit when filter enabled`() {
        val equatorial = Equatorial(raDeg = 101.29, decDeg = -16.72)
        // mag 6.0 > CLASS_9 darkNelm (4.0) → below limit
        CardRepository.update("faint1", CardRepository.Entry.Ready(starModel("faint1", equatorial, magnitude = 6.0)))
        VisibilitySettings.enabled.value = true
        VisibilitySettings.bortle.value = Bortle.CLASS_9
        VisibilitySettings.bortleSource.value = BortleSource.MANUAL

        val vm = makeVm(
            "faint1",
            FakeLocationPrefs(MutableStateFlow(GeoPoint(latDeg = 51.5, lonDeg = -0.1))),
            MutableStateFlow(null),
        )

        val state = vm.state.value as CardUiState.Ready
        assertTrue(state.belowVisibilityLimit, "expected belowVisibilityLimit for faint star under CLASS_9 limit")
        assertNotNull(state.limitingMag)
    }

    @Test
    fun `belowVisibilityLimit is false when filter is disabled`() {
        val equatorial = Equatorial(raDeg = 101.29, decDeg = -16.72)
        CardRepository.update("faint2", CardRepository.Entry.Ready(starModel("faint2", equatorial, magnitude = 6.0)))
        VisibilitySettings.enabled.value = false
        VisibilitySettings.bortle.value = Bortle.CLASS_9
        VisibilitySettings.bortleSource.value = BortleSource.MANUAL

        val vm = makeVm(
            "faint2",
            FakeLocationPrefs(MutableStateFlow(GeoPoint(latDeg = 51.5, lonDeg = -0.1))),
            MutableStateFlow(null),
        )

        val state = vm.state.value as CardUiState.Ready
        assertFalse(state.belowVisibilityLimit, "filter off → belowVisibilityLimit must be false")
    }

    @Test
    fun `bright star is not flagged even when filter is enabled`() {
        val equatorial = Equatorial(raDeg = 101.29, decDeg = -16.72)
        // mag 0.0 < any plausible limiting magnitude → not below limit
        CardRepository.update("bright1", CardRepository.Entry.Ready(starModel("bright1", equatorial, magnitude = 0.0)))
        VisibilitySettings.enabled.value = true
        VisibilitySettings.bortle.value = Bortle.CLASS_9
        VisibilitySettings.bortleSource.value = BortleSource.MANUAL

        val vm = makeVm(
            "bright1",
            FakeLocationPrefs(MutableStateFlow(GeoPoint(latDeg = 51.5, lonDeg = -0.1))),
            MutableStateFlow(null),
        )

        val state = vm.state.value as CardUiState.Ready
        assertFalse(state.belowVisibilityLimit, "bright star (mag 0) must never be flagged as below limit")
    }

    @Test
    fun `constellation with null magnitude is never flagged`() {
        CardRepository.update("const1", CardRepository.Entry.Ready(constModel("const1")))
        VisibilitySettings.enabled.value = true
        VisibilitySettings.bortle.value = Bortle.CLASS_9
        VisibilitySettings.bortleSource.value = BortleSource.MANUAL

        val vm = makeVm(
            "const1",
            FakeLocationPrefs(MutableStateFlow(GeoPoint(latDeg = 51.5, lonDeg = -0.1))),
            MutableStateFlow(null),
        )

        val state = vm.state.value as CardUiState.Ready
        assertFalse(state.belowVisibilityLimit, "constellation (null magnitude) must never be flagged")
    }

    @Test
    fun `no resolved location yields limitingMag null and belowVisibilityLimit false`() {
        val equatorial = Equatorial(raDeg = 101.29, decDeg = -16.72)
        CardRepository.update("faint3", CardRepository.Entry.Ready(starModel("faint3", equatorial, magnitude = 6.0)))
        VisibilitySettings.enabled.value = true
        VisibilitySettings.bortle.value = Bortle.CLASS_9
        VisibilitySettings.bortleSource.value = BortleSource.MANUAL

        val vm = makeVm(
            "faint3",
            FakeLocationPrefs(MutableStateFlow(null)),
            MutableStateFlow(null),
        )

        val state = vm.state.value as CardUiState.Ready
        assertNull(state.limitingMag, "no location → limitingMag must be null")
        assertFalse(state.belowVisibilityLimit, "no location → belowVisibilityLimit must be false")
    }

    private companion object {
        val FIXED_INSTANT: Instant = Instant.parse("2024-06-01T22:00:00Z")
    }
}

// ---------------------------------------------------------------------------------
// Fake LocationPrefs
// ---------------------------------------------------------------------------------

private class FakeLocationPrefs(
    private val manualFlow: MutableStateFlow<GeoPoint?> = MutableStateFlow(null),
) : LocationPrefs {
    override val manualPointFlow: Flow<GeoPoint?> = manualFlow
    override val usePhoneFallbackFlow: Flow<Boolean> = flowOf(false)
    override val shareLocationWithWatchFlow: Flow<Boolean> = flowOf(false)
    override suspend fun setManual(point: GeoPoint?) { manualFlow.value = point }
    override suspend fun setUsePhoneFallback(usePhoneFallback: Boolean) {}
    override suspend fun setShareLocationWithWatch(share: Boolean) {}
}

// ---------------------------------------------------------------------------------
// Fake EphemerisComputer — sun and moon fixed at south pole (below horizon everywhere
// above lat -90), so no twilight or moon penalty; limitingMag == darkNelm exactly.
// ---------------------------------------------------------------------------------

private class FakeEphemerisComputer : EphemerisComputer {
    override fun compute(body: Body, instant: Instant): Ephemeris =
        Ephemeris(
            eq = Equatorial(raDeg = 0.0, decDeg = -90.0),
            distanceAu = 1.0,
            phase = 0.0,
        )
}
