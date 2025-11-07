package dev.pointtosky.wear.identify

import androidx.compose.runtime.Composable
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.wear.compose.navigation.composable
import android.net.Uri
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.SimpleEphemerisComputer
import dev.pointtosky.core.catalog.runtime.CatalogRepository
import dev.pointtosky.core.location.api.LocationRepository
import dev.pointtosky.wear.sensors.orientation.OrientationRepository
import dev.pointtosky.wear.settings.AimIdentifySettingsDataStore

class IdentifyViewModelFactory(
    private val orientationRepository: OrientationRepository,
    private val locationRepository: LocationRepository,
    private val catalogRepository: CatalogRepository,
    private val settings: AimIdentifySettingsDataStore,
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(IdentifyViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return IdentifyViewModel(
                orientationRepository = orientationRepository,
                locationRepository = locationRepository,
                identifySolver = catalogRepository.identifySolver,
                constellations = catalogRepository.constellationBoundaries,
                ephemeris = SimpleEphemerisComputer(),
                timeSource = dev.pointtosky.core.time.SystemTimeSource(periodMs = 200L),
                settings = settings,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class ${'$'}modelClass")
    }
}

fun NavGraphBuilder.identifyDestination(
    factory: IdentifyViewModelFactory,
    onOpenCard: ((IdentifyUiState) -> Unit)?,
) {
    composable("identify") {
        IdentifyRoute(factory = factory, onOpenCard = onOpenCard)
    }
}
/**
 * Построить строку маршрута на экран карточки (S6.D) из текущего состояния Identify.
 * Маршрут использует только строковые query‑параметры (без Parcelize/новых зависимостей).
 */
fun buildCardRouteFrom(state: IdentifyUiState): String {
    val base = "card"
    return when (state.type) {
        IdentifyType.STAR -> {
            val name = Uri.encode(state.title)
            val mag = state.magnitude?.let { String.format("%.2f", it) }
            val ra = state.objectEq?.raDeg?.let { String.format("%.6f", it) }
            val dec = state.objectEq?.decDeg?.let { String.format("%.6f", it) }
            listOfNotNull(
                "type=STAR",
                "name=$name",
                mag?.let { "mag=$it" },
                ra?.let { "ra=$it" },
                dec?.let { "dec=$it" },
                state.objectId?.let { "id=${Uri.encode(it)}" },
            ).joinToString(prefix = "$base?", separator = "&")
        }
        IdentifyType.PLANET, IdentifyType.MOON -> {
            val body = state.body?.name ?: Body.MOON.name
            "$base?type=${state.type.name}&body=$body"
        }
        IdentifyType.CONST -> {
            val iau = Uri.encode(state.constellationIau ?: "")
            "$base?type=CONST&iau=$iau"
        }
    }
}

