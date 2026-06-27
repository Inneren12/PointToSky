package dev.pointtosky.mobile.ar

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.identify.ConstellationBoundaries
import dev.pointtosky.core.astro.identify.IdentifySolver
import dev.pointtosky.core.astro.identify.SkyCatalog
import dev.pointtosky.core.astro.identify.SkyObject
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.mobile.visibility.VisibilitySettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ArViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        VisibilitySettings.reset()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        VisibilitySettings.reset()
    }

    private fun makeVm(
        manualPoint: GeoPoint? = null,
        devicePoint: GeoPoint? = null,
    ) = ArViewModel(
        identifySolver = fakeIdentifySolver,
        astroLoader = null,
        locationPrefs = FakeArLocationPrefs(MutableStateFlow(manualPoint)),
        deviceLocationFlow = MutableStateFlow(devicePoint),
        ioDispatcher = testDispatcher,
    )

    @Test
    fun `device location alone resolves when manual is null`() {
        val edmonton = GeoPoint(latDeg = 53.55, lonDeg = -113.49)
        val vm = makeVm(devicePoint = edmonton)

        val snapshot = vm.locationSnapshot.value

        assertTrue(snapshot.resolved, "device GPS alone should yield resolved = true")
        assertEquals(ArViewModel.LocationSource.DEVICE, snapshot.source)
        assertEquals(edmonton, snapshot.point)
    }

    @Test
    fun `manual point wins over device when both present`() {
        val manual = GeoPoint(latDeg = 51.5, lonDeg = -0.1)
        val device = GeoPoint(latDeg = 53.55, lonDeg = -113.49)
        val vm = makeVm(manualPoint = manual, devicePoint = device)

        val snapshot = vm.locationSnapshot.value

        assertTrue(snapshot.resolved)
        assertEquals(ArViewModel.LocationSource.MANUAL, snapshot.source)
        assertEquals(manual, snapshot.point)
    }

    @Test
    fun `no location yields unresolved default`() {
        val vm = makeVm()

        val snapshot = vm.locationSnapshot.value

        assertFalse(snapshot.resolved, "no location should not resolve")
        assertEquals(ArViewModel.LocationSource.DEFAULT, snapshot.source)
    }

    private companion object {
        val fakeIdentifySolver = IdentifySolver(
            object : SkyCatalog {
                override fun nearby(
                    center: Equatorial,
                    radiusDeg: Double,
                    magLimit: Double?,
                ) = emptyList<SkyObject>()
            },
            object : ConstellationBoundaries {
                override fun findByEq(eq: Equatorial): String? = null
            },
        )
    }
}

private class FakeArLocationPrefs(
    private val manualFlow: MutableStateFlow<GeoPoint?>,
) : LocationPrefs {
    override val manualPointFlow: Flow<GeoPoint?> = manualFlow
    override val usePhoneFallbackFlow: Flow<Boolean> = flowOf(false)
    override val shareLocationWithWatchFlow: Flow<Boolean> = flowOf(false)
    override suspend fun setManual(point: GeoPoint?) {
        manualFlow.value = point
    }
    override suspend fun setUsePhoneFallback(usePhoneFallback: Boolean) {}
    override suspend fun setShareLocationWithWatch(share: Boolean) {}
}
