package dev.pointtosky.mobile.ar

import android.content.res.AssetManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.pointtosky.core.astro.catalog.ConstellationId
import dev.pointtosky.core.astro.catalog.PtskCatalogLoader
import dev.pointtosky.core.astro.catalog.StarRecord
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.identify.IdentifySolver
import dev.pointtosky.core.astro.identify.SkyObjectOrConstellation
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.core.time.SystemTimeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale

class ArViewModel(
    private val identifySolver: IdentifySolver,
    private val astroLoader: PtskCatalogLoader,
    locationPrefs: LocationPrefs,
    timeSource: SystemTimeSource = SystemTimeSource(periodMs = 1_000L),
    /** DI-диспетчер вместо прямого Dispatchers.IO (detekt: InjectDispatcher) */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val staticStars = MutableStateFlow<List<ArStar>?>(null)
    private val astroCatalog = MutableStateFlow<AstroCatalogState?>(null)
    private val showConstellations = MutableStateFlow(true)
    private val showAsterisms = MutableStateFlow(true)
    private val magLimit = MutableStateFlow<Double>(6.0)
    private val asterismState =
        MutableStateFlow(
            AsterismUiState(
                isEnabled = true,
                highlighted = null,
                available = emptyList(),
            ),
        )

    private val manualPointFlow = locationPrefs.manualPointFlow
    private val locationSnapshot: StateFlow<LocationSnapshot> =
        manualPointFlow
            .map { manual ->
                if (manual != null) {
                    LocationSnapshot(point = manual, resolved = true)
                } else {
                    LocationSnapshot(point = DEFAULT_LOCATION, resolved = false)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = LocationSnapshot(point = DEFAULT_LOCATION, resolved = false),
            )

    val state: StateFlow<ArUiState> =
        combine(
            staticStars.filterNotNull(),
            astroCatalog,
            locationSnapshot,
            timeSource.ticks,
            showConstellations,
            showAsterisms,
            asterismState,
            magLimit,
        ) { values: Array<Any?> ->
            @Suppress("UNCHECKED_CAST")
            val stars = values[0] as List<ArStar>
            val catalog = values[1] as AstroCatalogState?
            val location = values[2] as LocationSnapshot
            val instant = values[3] as Instant
            val showConst = values[4] as Boolean
            val showAster = values[5] as Boolean
            val asterisms = values[6] as AsterismUiState
            val magLimitValue = values[7] as Double

            buildState(
                stars = stars,
                catalog = catalog,
                location = location,
                instant = instant,
                showConstellations = showConst,
                showAsterisms = showAster,
                asterismUiState = asterisms,
                magLimit = magLimitValue,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = ArUiState.Loading,
        )

    init {
        viewModelScope.launch(ioDispatcher) {
            val catalogState = loadAstroCatalog()
            astroCatalog.value = catalogState
            staticStars.value = catalogState?.catalog?.allStars()?.map(::mapToArStar).orEmpty()
        }
    }

    private fun buildState(
        stars: List<ArStar>,
        catalog: AstroCatalogState?,
        location: LocationSnapshot,
        instant: Instant,
        showConstellations: Boolean,
        showAsterisms: Boolean,
        asterismUiState: AsterismUiState,
        magLimit: Double,
    ): ArUiState {
        val lstDeg = lstAt(instant, location.point.lonDeg).lstDeg
        return ArUiState.Ready(
            instant = instant,
            location = location.point,
            locationResolved = location.resolved,
            lstDeg = lstDeg,
            stars = stars,
            catalog = catalog,
            showConstellations = showConstellations,
            showAsterisms = showAsterisms,
            asterismUiState = asterismUiState,
            magLimit = magLimit,
        )
    }

    fun setShowConstellations(enabled: Boolean) {
        showConstellations.value = enabled
    }

    fun setShowAsterisms(enabled: Boolean) {
        showAsterisms.value = enabled
        asterismState.update { current -> current.copy(isEnabled = enabled) }
    }

    fun updateAsterismContext(available: List<AsterismSummary>, highlighted: AsterismId?) {
        val current = asterismState.value
        val next = current.copy(available = available, highlighted = highlighted)
        if (current != next) {
            asterismState.value = next
        }
    }

    fun setMagLimit(value: Double) {
        magLimit.value = value.coerceIn(0.0, 8.0)
    }

    fun resolveConstellationId(equatorial: Equatorial): ConstellationId? {
        val catalogState = astroCatalog.value ?: return null
        return when (val result = identifySolver.findBest(equatorial, searchRadiusDeg = 5.0, magLimit = 5.5)) {
            is SkyObjectOrConstellation.Constellation ->
                catalogState.constellationByAbbr[result.iauCode.uppercase(Locale.ROOT)]

            is SkyObjectOrConstellation.Object -> null
        }
    }

    private data class LocationSnapshot(
        val point: GeoPoint,
        val resolved: Boolean,
    )

    data class ArStar(
        val id: Int,
        val label: String?,
        val magnitude: Double,
        val equatorial: Equatorial,
    )

    private suspend fun loadAstroCatalog(): AstroCatalogState? =
        withContext(ioDispatcher) {
            val catalog = astroLoader.load() ?: return@withContext null
            val stars = catalog.allStars()
            val constellationByAbbr =
                (0..87).associate { index ->
                    val meta = catalog.getConstellationMeta(ConstellationId(index))
                    meta.abbreviation.uppercase(Locale.ROOT) to meta.id
                }
            AstroCatalogState(
                catalog = catalog,
                starsById = stars.associateBy { it.id.raw },
                constellationByAbbr = constellationByAbbr,
                skeletonLines = buildConstellationSkeletonLines(stars),
            )
        }

    companion object {
        private val DEFAULT_LOCATION = GeoPoint(latDeg = 0.0, lonDeg = 0.0)
    }
}

sealed interface ArUiState {
    object Loading : ArUiState

    data class Ready(
        val instant: Instant,
        val location: GeoPoint,
        val locationResolved: Boolean,
        val lstDeg: Double,
        val stars: List<ArViewModel.ArStar>,
        val catalog: AstroCatalogState?,
        val showConstellations: Boolean,
        val showAsterisms: Boolean,
        val asterismUiState: AsterismUiState,
        val magLimit: Double,
    ) : ArUiState
}

private fun mapToArStar(star: StarRecord): ArViewModel.ArStar =
    ArViewModel.ArStar(
        id = star.id.raw,
        label = star.name,
        magnitude = star.magnitude.toDouble(),
        equatorial = Equatorial(star.rightAscensionDeg.toDouble(), star.declinationDeg.toDouble()),
    )
}

class ArViewModelFactory(
    private val identifySolver: IdentifySolver,
    private val assetManager: AssetManager,
    private val locationPrefs: LocationPrefs,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ArViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ArViewModel(
                identifySolver = identifySolver,
                astroLoader = PtskCatalogLoader(assetManager),
                locationPrefs = locationPrefs,
            ) as T
        }

        throw IllegalArgumentException("Unknown ViewModel class $modelClass")
    }
}
