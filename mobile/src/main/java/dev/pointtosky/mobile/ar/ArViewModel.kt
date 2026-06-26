package dev.pointtosky.mobile.ar

import android.content.res.AssetManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.pointtosky.core.astro.catalog.ConstellationId
import dev.pointtosky.core.astro.catalog.PtskCatalogLoader
import dev.pointtosky.core.astro.catalog.StarRecord
import dev.pointtosky.core.astro.catalog.isRenderablePoint
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.EphemerisComputer
import dev.pointtosky.core.astro.ephem.SimpleEphemerisComputer
import dev.pointtosky.core.astro.identify.IdentifySolver
import dev.pointtosky.core.astro.identify.SkyObjectOrConstellation
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.transform.raDecToAltAz
import dev.pointtosky.core.astro.visibility.Bortle
import dev.pointtosky.core.astro.visibility.LightPollutionGrid
import dev.pointtosky.core.astro.visibility.estimateLimitingMagnitude
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.core.time.SystemTimeSource
import dev.pointtosky.mobile.visibility.BortleSource
import dev.pointtosky.mobile.visibility.LightPollutionProvider
import dev.pointtosky.mobile.visibility.VisibilitySettings
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

enum class ConstellationMode { OFF, ASTERISMS, FIGURE }

class ArViewModel(
    private val identifySolver: IdentifySolver,
    private val astroLoader: PtskCatalogLoader,
    locationPrefs: LocationPrefs,
    timeSource: SystemTimeSource = SystemTimeSource(periodMs = 1_000L),
    private val ephemerisComputer: EphemerisComputer = SimpleEphemerisComputer(),
    /** DI-диспетчер вместо прямого Dispatchers.IO (detekt: InjectDispatcher) */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    lightPollutionGrid: StateFlow<LightPollutionGrid?> = LightPollutionProvider.grid(),
) : ViewModel() {
    private val staticStars = MutableStateFlow<List<ArStar>?>(null)
    private val astroCatalog = MutableStateFlow<AstroCatalogState?>(null)
    private val constellationMode = MutableStateFlow(ConstellationMode.ASTERISMS)
    private val proMode = MutableStateFlow(false)
    private val magLimit = MutableStateFlow<Double>(6.0)
    private val showStarLabels = MutableStateFlow(true)
    private val showStarPoints = MutableStateFlow(true)
    private val reticleTargetOnly = MutableStateFlow(false)
    private val visibilityFilterEnabled = VisibilitySettings.enabled
    private val bortle = VisibilitySettings.bortle
    private val bortleSource = VisibilitySettings.bortleSource
    private val lightPollutionGrid = lightPollutionGrid
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
            constellationMode,
            proMode,
            asterismState,
            magLimit,
            showStarLabels,
            showStarPoints,
            reticleTargetOnly,
            visibilityFilterEnabled,
            bortle,
            bortleSource,
            lightPollutionGrid,
        ) { values: Array<Any?> ->
            @Suppress("UNCHECKED_CAST")
            val stars = values[0] as List<ArStar>
            val catalog = values[1] as AstroCatalogState?
            val location = values[2] as LocationSnapshot
            val instant = values[3] as Instant
            val mode = values[4] as ConstellationMode
            val pro = values[5] as Boolean
            val asterisms = values[6] as AsterismUiState
            val magLimitValue = values[7] as Double
            val showStarLabelsValue = values[8] as Boolean
            val showStarPointsValue = values[9] as Boolean
            val reticleTargetOnlyValue = values[10] as Boolean
            val visibilityFilterValue = values[11] as Boolean
            val bortleValue = values[12] as Bortle
            val bortleSourceValue = values[13] as BortleSource
            val gridValue = values[14] as LightPollutionGrid?

            buildState(
                stars = stars,
                catalog = catalog,
                location = location,
                instant = instant,
                constellationMode = mode,
                proMode = pro,
                asterismUiState = asterisms,
                magLimit = magLimitValue,
                showStarLabels = showStarLabelsValue,
                showStarPoints = showStarPointsValue,
                reticleTargetOnly = reticleTargetOnlyValue,
                visibilityFilter = visibilityFilterValue,
                bortle = bortleValue,
                bortleSource = bortleSourceValue,
                grid = gridValue,
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
            staticStars.value =
                catalogState?.catalog?.allStars()
                    ?.filter { it.isRenderablePoint() }
                    ?.map(::mapToArStar)
                    .orEmpty()
        }
    }

    private fun buildState(
        stars: List<ArStar>,
        catalog: AstroCatalogState?,
        location: LocationSnapshot,
        instant: Instant,
        constellationMode: ConstellationMode,
        proMode: Boolean,
        asterismUiState: AsterismUiState,
        magLimit: Double,
        showStarLabels: Boolean,
        showStarPoints: Boolean,
        reticleTargetOnly: Boolean,
        visibilityFilter: Boolean,
        bortle: Bortle,
        bortleSource: BortleSource,
        grid: LightPollutionGrid?,
    ): ArUiState {
        val lstDeg = lstAt(instant, location.point.lonDeg).lstDeg
        val moon = ephemerisComputer.compute(Body.MOON, instant)
        val sun = ephemerisComputer.compute(Body.SUN, instant)
        val moonAlt = raDecToAltAz(
            eq = moon.eq,
            lstDeg = lstDeg,
            latDeg = location.point.latDeg,
            applyRefraction = false,
        ).altDeg
        val sunAlt = raDecToAltAz(
            eq = sun.eq,
            lstDeg = lstDeg,
            latDeg = location.point.latDeg,
            applyRefraction = false,
        ).altDeg
        val autoBortle: Bortle? =
            if (bortleSource == BortleSource.AUTO && location.resolved) {
                grid?.bortleAt(location.point.latDeg, location.point.lonDeg)
            } else null
        val effectiveBortle = autoBortle ?: bortle
        val limitingMag = estimateLimitingMagnitude(
            darkNelm = effectiveBortle.darkNelm,
            moonAltitudeDeg = moonAlt,
            moonIllumination = moon.phase ?: 0.0,
            sunAltitudeDeg = sunAlt,
        )
        // A user magLimit of 0 means "no user cap" (the slider's off sentinel); treat it as +∞ so it
        // doesn't clamp the visibility limit. The visibility limit may legitimately be negative
        // (e.g. daytime floors at −4), and must still suppress stars.
        val userCap = if (magLimit <= 0.0) Double.POSITIVE_INFINITY else magLimit
        val effectiveMagLimit = if (visibilityFilter) minOf(userCap, limitingMag) else userCap
        return ArUiState.Ready(
            instant = instant,
            location = location.point,
            locationResolved = location.resolved,
            lstDeg = lstDeg,
            stars = stars,
            catalog = catalog,
            showConstellations = constellationMode == ConstellationMode.FIGURE,
            showAsterisms = constellationMode == ConstellationMode.ASTERISMS,
            asterismUiState = asterismUiState,
            magLimit = magLimit,
            effectiveMagLimit = effectiveMagLimit,
            limitingMag = limitingMag,
            visibilityFilterEnabled = visibilityFilter,
            bortle = bortle,
            bortleSource = bortleSource,
            autoBortle = autoBortle,
            showStarLabels = showStarLabels,
            showStarPoints = showStarPoints,
            reticleTargetOnly = reticleTargetOnly,
            constellationMode = constellationMode,
            proMode = proMode,
        )
    }

    fun setConstellationMode(mode: ConstellationMode) {
        constellationMode.value = mode
        asterismState.update { current -> current.copy(isEnabled = mode == ConstellationMode.ASTERISMS) }
    }

    fun setProMode(enabled: Boolean) {
        proMode.value = enabled
    }

    fun setShowStarPoints(enabled: Boolean) {
        showStarPoints.value = enabled
    }

    fun setReticleTargetOnly(enabled: Boolean) {
        reticleTargetOnly.value = enabled
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

    fun setVisibilityFilterEnabled(enabled: Boolean) {
        VisibilitySettings.enabled.value = enabled
    }

    fun setBortle(value: Bortle) {
        VisibilitySettings.bortle.value = value
    }

    fun setBortleSource(s: BortleSource) {
        VisibilitySettings.bortleSource.value = s
    }

    fun setShowStarLabels(enabled: Boolean) {
        showStarLabels.value = enabled
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
        val bv: Float? = null,
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
        val showStarLabels: Boolean,
        val showStarPoints: Boolean = true,
        val reticleTargetOnly: Boolean = false,
        val constellationMode: ConstellationMode = ConstellationMode.ASTERISMS,
        val proMode: Boolean = false,
        val effectiveMagLimit: Double = 6.0,
        val limitingMag: Double? = null,
        val visibilityFilterEnabled: Boolean = false,
        val bortle: Bortle = Bortle.CLASS_4,
        val bortleSource: BortleSource = BortleSource.AUTO,
        val autoBortle: Bortle? = null,
    ) : ArUiState
}

private fun mapToArStar(star: StarRecord): ArViewModel.ArStar =
    ArViewModel.ArStar(
        id = star.id.raw,
        label = star.name,
        magnitude = star.magnitude.toDouble(),
        equatorial = Equatorial(star.rightAscensionDeg.toDouble(), star.declinationDeg.toDouble()),
        bv = star.bv,
    )

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
