package dev.pointtosky.wear.astro

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.SimpleEphemerisComputer
import dev.pointtosky.core.astro.identify.IdentifySolver
import dev.pointtosky.core.catalog.CatalogAdapter
import dev.pointtosky.core.catalog.constellation.FakeConstellationBoundaries
import dev.pointtosky.core.catalog.star.FakeStarCatalog
import dev.pointtosky.core.location.api.LocationRepository
import dev.pointtosky.wear.R
import dev.pointtosky.wear.sensors.orientation.OrientationRepository
import java.util.Locale

@Composable
fun AstroDebugRoute(
    factory: AstroDebugViewModelFactory,
    modifier: Modifier = Modifier,
) {
    val viewModel: AstroDebugViewModel = viewModel(factory = factory)
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    AstroDebugScreen(
        state = state,
        onTargetSelected = viewModel::selectTarget,
        modifier = modifier,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AstroDebugScreen(
    state: AstroDebugUiState,
    onTargetSelected: (Body) -> Unit,
    modifier: Modifier = Modifier,
) {
    val az = state.horizontal.azDeg
    val alt = state.horizontal.altDeg
    val azAltText = stringResource(R.string.astro_debug_horizontal, az, alt)
    val equatorialText = state.equatorial?.let { eq ->
        stringResource(R.string.astro_debug_equatorial, eq.raDeg, eq.decDeg)
    } ?: stringResource(id = R.string.astro_debug_value_unknown)
    val lstDegText = state.lstDeg?.let { lst ->
        stringResource(R.string.astro_debug_lst_degrees, lst)
    } ?: stringResource(id = R.string.astro_debug_lst_unknown)
    val lstHmsText = state.lstHms ?: stringResource(id = R.string.astro_debug_value_unknown)
    val bestMatchText = when (val match = state.bestMatch) {
        is AstroBestMatch.Object -> {
            val magnitude = match.magnitude?.let { String.format(Locale.US, "%.2f", it) }
                ?: stringResource(id = R.string.astro_debug_value_unknown)
            val constellation = match.constellationCode
                ?: stringResource(id = R.string.astro_debug_constellation_unknown)
            stringResource(
                id = R.string.astro_debug_best_match_object,
                match.label ?: stringResource(id = R.string.astro_debug_best_match_unknown_label),
                magnitude,
                constellation,
            )
        }
        is AstroBestMatch.Constellation -> {
            stringResource(id = R.string.astro_debug_best_match_constellation, match.code)
        }
        null -> stringResource(id = R.string.astro_debug_best_match_unknown)
    }
    val aimErrorText = state.aimError?.let { error ->
        stringResource(R.string.astro_debug_aim_error, error.dAzDeg, error.dAltDeg)
    } ?: stringResource(id = R.string.astro_debug_aim_error_unknown)

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = stringResource(id = R.string.astro_debug_title),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
            )
            Text(
                text = lstDegText,
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.astro_debug_lst_time, lstHmsText),
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = azAltText,
                style = MaterialTheme.typography.body2,
            )
            Text(
                text = equatorialText,
                style = MaterialTheme.typography.body2,
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = bestMatchText,
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                maxItemsInEachRow = 2,
            ) {
                Body.entries.forEach { body ->
                    val selected = body == state.target
                    val colors = if (selected) {
                        ChipDefaults.primaryChipColors()
                    } else {
                        ChipDefaults.secondaryChipColors()
                    }
                    CompactChip(
                        onClick = { onTargetSelected(body) },
                        label = {
                            Text(text = stringResource(id = body.toLabelRes()))
                        },
                        icon = {
                            if (selected) {
                                Icon(
                                    imageVector = Icons.Rounded.Refresh,
                                    contentDescription = null,
                                )
                            }
                        },
                        colors = colors,
                    )
                }
            }
            Text(
                text = stringResource(
                    id = R.string.astro_debug_target_summary,
                    stringResource(id = state.target.toLabelRes()),
                ),
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
            )
            Text(
                text = aimErrorText,
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun Body.toLabelRes(): Int = when (this) {
    Body.SUN -> R.string.astro_debug_target_sun
    Body.MOON -> R.string.astro_debug_target_moon
    Body.JUPITER -> R.string.astro_debug_target_jupiter
    Body.SATURN -> R.string.astro_debug_target_saturn
}

class AstroDebugViewModelFactory(
    private val orientationRepository: OrientationRepository,
    private val locationRepository: LocationRepository,
    private val ephemerisComputer: SimpleEphemerisComputer = SimpleEphemerisComputer(),
    private val starCatalog: FakeStarCatalog = FakeStarCatalog(),
    private val constellationBoundaries: FakeConstellationBoundaries = FakeConstellationBoundaries(),
) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AstroDebugViewModel::class.java)) {
            val adapter = CatalogAdapter(starCatalog, constellationBoundaries)
            val solver = IdentifySolver(adapter, adapter)
            @Suppress("UNCHECKED_CAST")
            return AstroDebugViewModel(
                orientationRepository = orientationRepository,
                locationRepository = locationRepository,
                ephemerisComputer = ephemerisComputer,
                identifySolver = solver,
                constellations = adapter,
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class ${'$'}modelClass")
    }
}
