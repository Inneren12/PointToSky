package dev.pointtosky.wear.identify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.navigation.composable
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.SimpleEphemerisComputer
import dev.pointtosky.core.astro.time.lstAt
import dev.pointtosky.core.astro.transform.raDecToAltAz
import dev.pointtosky.core.location.api.LocationRepository
import dev.pointtosky.core.time.SystemTimeSource
import java.time.Instant
import java.util.Locale
import dev.pointtosky.core.location.model.LocationFix

/**
 * Карточка объекта (S6.D): поддерживает STAR / PLANET / MOON / CONST.
 * Параметры маршрута:
 *  - type={STAR|PLANET|MOON|CONST}
 *  - STAR: name, mag?, ra, dec, id?
 *  - PLANET/MOON: body={MOON|JUPITER|SATURN|SUN?}
 *  - CONST: iau
 */
@Composable
fun CardRoute(
    type: String?,
    name: String?,
    mag: String?,
    ra: String?,
    dec: String?,
    body: String?,
    iau: String?,
    locationRepository: LocationRepository,
    ephemeris: SimpleEphemerisComputer = SimpleEphemerisComputer(),
    timeSource: SystemTimeSource = SystemTimeSource(periodMs = 1000L),
    modifier: Modifier = Modifier,
) {
    var locationFix: LocationFix? by remember { mutableStateOf<LocationFix?>(null) }
    // корректный сбор локации: initial from getLastKnown(), затем поток fixes
    LaunchedEffect(locationRepository) {
        runCatching { locationRepository.getLastKnown() }
            .onSuccess { fix -> if (fix != null) locationFix = fix }
        locationRepository.fixes.collect { fix -> locationFix = fix }
    }

    val instant: Instant by timeSource.ticks.collectAsStateWithLifecycle(initialValue = timeSource.now())
    val point = locationFix?.point

    val header = when (type) {
        "STAR" -> name ?: "Star"
        "PLANET" -> body ?: "Planet"
        "MOON" -> "Moon"
        "CONST" -> iau ?: "Constellation"
        else -> "—"
    }

    // Вычисляем текущие координаты/представление для карточки
    val details: String = when (type) {
        "STAR" -> {
            val raDeg = ra?.toDoubleOrNull()
            val decDeg = dec?.toDoubleOrNull()
            if (raDeg != null && decDeg != null && point != null) {
                val lstDeg = lstAt(instant, point.lonDeg).lstDeg
                val hor = raDecToAltAz(Equatorial(raDeg, decDeg), lstDeg, point.latDeg, applyRefraction = false)
                val mText = mag ?: "—"
                "Type: STAR\nm: $mText\nRA/Dec: ${fmt(raDeg)}° / ${fmt(decDeg)}°\nAz/Alt: ${fmt(hor.azDeg)}° / ${fmt(hor.altDeg)}°"
            } else {
                "Type: STAR\nm: ${mag ?: "—"}\nRA/Dec: ${ra ?: "—"} / ${dec ?: "—"}"
            }
        }
        "PLANET", "MOON" -> {
            val bodyEnum = runCatching { Body.valueOf(body ?: "MOON") }.getOrNull() ?: Body.MOON
            if (point != null) {
                val eq = ephemeris.compute(bodyEnum, instant).eq
                val lstDeg = lstAt(instant, point.lonDeg).lstDeg
                val hor = raDecToAltAz(eq, lstDeg, point.latDeg, applyRefraction = false)
                "Type: $type\nBody: ${bodyEnum.name}\nRA/Dec: ${fmt(eq.raDeg)}° / ${fmt(eq.decDeg)}°\nAz/Alt: ${fmt(hor.azDeg)}° / ${fmt(hor.altDeg)}°"
            } else {
                "Type: $type\nBody: ${bodyEnum.name}"
            }
        }
        "CONST" -> {
            "Type: CONST\nIAU: ${iau ?: "—"}"
        }
        else -> {
            "—"
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = header, style = MaterialTheme.typography.title3)
        Text(text = details, style = MaterialTheme.typography.body2)
    }
}

private fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v)

/**
 * Регистрация экрана карточки с аргументами.
 * Пример route: card?type=STAR&name=Rigel&mag=0.1&ra=78.634&dec=-8.2016
 */
fun androidx.navigation.NavGraphBuilder.cardDestination(
    locationRepository: LocationRepository,
) {
    composable(
        route = "card?type={type}&name={name}&mag={mag}&ra={ra}&dec={dec}&body={body}&iau={iau}",
        arguments = listOf(
            navArgument("type") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("name") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("mag") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("ra") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("dec") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("body") { type = NavType.StringType; nullable = true; defaultValue = null },
            navArgument("iau") { type = NavType.StringType; nullable = true; defaultValue = null },
        )
    ) { backStackEntry: NavBackStackEntry ->
        val args = backStackEntry.arguments
        CardRoute(
            type = args?.getString("type"),
            name = args?.getString("name"),
            mag = args?.getString("mag"),
            ra = args?.getString("ra"),
            dec = args?.getString("dec"),
            body = args?.getString("body"),
            iau = args?.getString("iau"),
            locationRepository = locationRepository,
        )
    }
}
