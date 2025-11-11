package dev.pointtosky.mobile.skymap

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.transform.raDecToAltAz
import dev.pointtosky.core.catalog.runtime.CatalogRepository
import dev.pointtosky.core.catalog.star.Star
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.core.time.SystemTimeSource
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

class SkyMapViewModel(
    private val catalogRepository: CatalogRepository,
    private val locationPrefs: LocationPrefs,
    context: Context,
    private val timeSource: SystemTimeSource = SystemTimeSource(periodMs = 1_000L),
) : ViewModel() {

    private val appContext = context.applicationContext

    private val staticData = MutableStateFlow<StaticSkyData?>(null)

    private val locationSnapshot: StateFlow<LocationSnapshot> = locationPrefs.manualPointFlow
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

    val state: StateFlow<SkyMapState> = staticData
        .filterNotNull()
        .combine(locationSnapshot) { data, location -> data to location }
        .combine(timeSource.ticks) { (data, location), instant ->
            buildState(data, location, instant)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = SkyMapState.Loading,
        )

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val stars = loadStars()
            val outlines = ConstellationOutlineLoader(appContext.assets).load()
            staticData.value = StaticSkyData(stars = stars, outlines = outlines)
        }
    }

    private suspend fun loadStars(): List<SkyStar> = withContext(Dispatchers.IO) {
        val all = catalogRepository.starCatalog.nearby(
            center = Equatorial(0.0, 0.0),
            radiusDeg = 180.0,
            magLimit = STAR_MAG_LIMIT,
        )
        all.distinctBy(Star::id).map { star ->
            SkyStar(
                id = star.id,
                name = star.name,
                designation = starLabel(star),
                magnitude = star.mag.toDouble(),
                equatorial = Equatorial(star.raDeg.toDouble(), star.decDeg.toDouble()),
                constellation = star.constellation,
            )
        }
    }

    private fun buildState(data: StaticSkyData, location: LocationSnapshot, instant: Instant): SkyMapState {
        val lst = lstAt(instant, location.point.lonDeg).lstDeg
        val lat = location.point.latDeg
        val projectedStars = data.stars.map { star ->
            val horizontal = raDecToAltAz(star.equatorial, lst, lat)
            ProjectedStar(
                id = star.id,
                name = star.name,
                designation = star.designation,
                magnitude = star.magnitude,
                equatorial = star.equatorial,
                horizontal = horizontal,
                constellation = star.constellation,
            )
        }
        val projectedConstellations = data.outlines.map { outline ->
            val polygons = outline.polygons.map { polygon ->
                polygon.map { vertex ->
                    raDecToAltAz(vertex, lst, lat, applyRefraction = false)
                }
            }
            ConstellationProjection(outline.iauCode, polygons)
        }
        return SkyMapState.Ready(
            instant = instant,
            location = location.point,
            locationResolved = location.resolved,
            stars = projectedStars,
            constellations = projectedConstellations,
        )
    }

    private data class StaticSkyData(
        val stars: List<SkyStar>,
        val outlines: List<ConstellationOutline>,
    )

    private data class LocationSnapshot(
        val point: GeoPoint,
        val resolved: Boolean,
    )

    private data class SkyStar(
        val id: Int,
        val name: String?,
        val designation: String?,
        val magnitude: Double,
        val equatorial: Equatorial,
        val constellation: String?,
    )

    companion object {
        private const val STAR_MAG_LIMIT = 4.2
        private val DEFAULT_LOCATION = GeoPoint(latDeg = 0.0, lonDeg = 0.0)
    }
}

sealed interface SkyMapState {
    object Loading : SkyMapState

    data class Ready(
        val instant: Instant,
        val location: GeoPoint,
        val locationResolved: Boolean,
        val stars: List<ProjectedStar>,
        val constellations: List<ConstellationProjection>,
    ) : SkyMapState
}

data class ProjectedStar(
    val id: Int,
    val name: String?,
    val designation: String?,
    val magnitude: Double,
    val equatorial: Equatorial,
    val horizontal: Horizontal,
    val constellation: String?,
) {
    val label: String? = name ?: designation
    val isAboveHorizon: Boolean get() = horizontal.altDeg >= 0.0
}

data class ConstellationProjection(
    val iauCode: String,
    val polygons: List<List<Horizontal>>,
)

private fun starLabel(star: Star): String? {
    return when {
        !star.name.isNullOrBlank() -> star.name
        !star.bayer.isNullOrBlank() && !star.constellation.isNullOrBlank() ->
            "${star.bayer!!.uppercase(Locale.ROOT)} ${star.constellation!!.uppercase(Locale.ROOT)}"
        !star.flamsteed.isNullOrBlank() -> star.flamsteed
        else -> null
    }
}

class SkyMapViewModelFactory(
    private val catalogRepository: CatalogRepository,
    private val locationPrefs: LocationPrefs,
    private val context: Context,
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SkyMapViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SkyMapViewModel(catalogRepository, locationPrefs, context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class ${'$'}modelClass")
    }
}
