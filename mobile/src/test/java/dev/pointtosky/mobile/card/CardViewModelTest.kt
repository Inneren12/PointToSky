package dev.pointtosky.mobile.card

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.prefs.LocationPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.time.Instant
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class CardViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        CardRepository.resetForTests()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        CardRepository.resetForTests()
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun makeVm(
        cardId: String,
        locationPrefs: LocationPrefs,
        deviceFlow: StateFlow<GeoPoint?>,
    ): CardViewModel =
        CardViewModel(
            cardId = cardId,
            repository = CardRepository,
            locationPrefs = locationPrefs,
            deviceLocationFlow = deviceFlow,
            clock = { FIXED_INSTANT },
        )

    private fun starModel(id: String, equatorial: Equatorial): CardObjectModel =
        CardObjectModel(
            id = id,
            type = CardObjectType.STAR,
            name = id,
            body = null,
            constellation = "TST",
            magnitude = 1.0,
            equatorial = equatorial,
            horizontal = null,
            bestWindow = null,
        )

    // -------------------------------------------------------------------------
    // Tests
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
