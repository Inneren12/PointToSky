package dev.pointtosky.mobile.ar

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.catalog.runtime.CatalogRepository
import dev.pointtosky.core.catalog.star.Star
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale

class ArViewModel(
    private val catalogRepository: CatalogRepository,
    locationPrefs: LocationPrefs,
    timeSource: SystemTimeSource = SystemTimeSource(periodMs = 1_000L),
    /** DI-диспетчер вместо прямого Dispatchers.IO (detekt: InjectDispatcher) */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val staticStars = MutableStateFlow<List<ArStar>?>(null)

    private val manualPointFlow = locationPrefs.manualPointFlow
    private val locationSnapshot: StateFlow<LocationSnapshot> =
        manualPointFlow
            .map { manual ->
                if (manual != null) {
                    LocationSnapshot(point = manual, resolved = true)
                } else {
                    LocationSnapshot(point = DEFAULT_LOCATION, resolved = false)
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = LocationSnapshot(point = DEFAULT_LOCATION, resolved = false),
            )

    val state: StateFlow<ArUiState> =
        staticStars
            .filterNotNull()
            .combine(locationSnapshot) { stars, location -> stars to location }
            .combine(timeSource.ticks) { (stars, location), instant ->
                buildState(stars, location, instant)
            }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = ArUiState.Loading,
            )

    init {
        viewModelScope.launch(ioDispatcher) {
            staticStars.value = loadStars()
        }
    }

    private suspend fun loadStars(): List<ArStar> =
        withContext(ioDispatcher) {
            val stars =
                catalogRepository.starCatalog.nearby(
                    center = Equatorial(0.0, 0.0),
                    radiusDeg = 180.0,
                    magLimit = STAR_MAG_LIMIT,
                )
            stars.distinctBy(Star::id).map { star ->
                ArStar(
                    id = star.id,
                    label = resolveLabel(star),
                    magnitude = star.mag.toDouble(),
                    equatorial = Equatorial(star.raDeg.toDouble(), star.decDeg.toDouble()),
                )
            }
        }

    private fun buildState(
        stars: List<ArStar>,
        location: LocationSnapshot,
        instant: Instant,
    ): ArUiState {
        val lstDeg = lstAt(instant, location.point.lonDeg).lstDeg
        return ArUiState.Ready(
            instant = instant,
            location = location.point,
            locationResolved = location.resolved,
            lstDeg = lstDeg,
            stars = stars,
        )
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

    companion object {
        private const val STAR_MAG_LIMIT = 2.5
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
    ) : ArUiState
}

private fun resolveLabel(star: Star): String? {
    // Избегаем '!!' — преобразуем в локальные безопасные переменные
    val name = star.name?.takeIf { it.isNotBlank() }
    if (name != null) return name

    val bayer = star.bayer?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
    val const = star.constellation?.takeIf { it.isNotBlank() }?.uppercase(Locale.ROOT)
    if (bayer != null && const != null) return "$bayer $const"

    val flam = star.flamsteed?.takeIf { it.isNotBlank() }
    if (flam != null) return flam
    return null
}

class ArViewModelFactory(
    private val catalogRepository: CatalogRepository,
    private val locationPrefs: LocationPrefs,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ArViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ArViewModel(catalogRepository, locationPrefs) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class $modelClass")
    }
}
