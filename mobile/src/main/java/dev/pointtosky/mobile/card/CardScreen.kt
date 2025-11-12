package dev.pointtosky.mobile.card

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.mobile.R
import dev.pointtosky.mobile.datalayer.AimTargetOption
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CardRoute(
    cardId: String,
    locationPrefs: LocationPrefs,
    onBack: () -> Unit,
    onSendAimTarget: (AimTargetOption) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val factory =
        remember(cardId, locationPrefs) {
            CardViewModelFactory(cardId = cardId, repository = CardRepository, locationPrefs = locationPrefs)
        }
    val viewModel: CardViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    CardScreen(
        state = state,
        onBack = onBack,
        onSendAimTarget = onSendAimTarget,
        onShare = onShare,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CardScreen(
    state: CardUiState,
    onBack: () -> Unit,
    onSendAimTarget: (AimTargetOption) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.card_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { padding ->
        when (state) {
            CardUiState.Loading ->
                Box(
                    modifier =
                        Modifier
                            .padding(padding)
                            .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }

            is CardUiState.Error ->
                Box(
                    modifier =
                        Modifier
                            .padding(padding)
                            .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(id = R.string.card_error_not_enough_data),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }

            is CardUiState.Ready ->
                CardContent(
                    state = state,
                    onSendAimTarget = onSendAimTarget,
                    onShare = onShare,
                    modifier =
                        Modifier
                            .padding(padding)
                            .fillMaxSize(),
                )
        }
    }
}

@Composable
private fun CardContent(
    state: CardUiState.Ready,
    onSendAimTarget: (AimTargetOption) -> Unit,
    onShare: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier =
            modifier
                .padding(horizontal = 24.dp, vertical = 16.dp)
                .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = typeLabel(state.type, state.body),
                style = MaterialTheme.typography.bodyLarge,
            )
        }

        DetailRow(
            label = stringResource(id = R.string.card_constellation_label),
            value = state.constellation.orPlaceholder(),
        )
        DetailRow(
            label = stringResource(id = R.string.card_magnitude_label),
            value = state.magnitude?.let { formatDegree(it, includeDegreeSymbol = false) } ?: "—",
        )
        DetailRow(
            label = stringResource(id = R.string.card_alt_az_label),
            value = formatHorizontal(state.horizontal),
        )
        DetailRow(
            label = stringResource(id = R.string.card_ra_dec_label),
            value = formatEquatorial(state.equatorial),
        )
        state.bestWindow?.let { window ->
            val windowText = formatWindow(window)
            if (windowText != null) {
                DetailRow(
                    label = stringResource(id = R.string.card_best_window_label),
                    value = windowText,
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { state.targetOption?.let(onSendAimTarget) },
            enabled = state.targetOption != null,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.card_set_target_button))
        }

        OutlinedButton(
            onClick = { onShare(state.shareText) },
            enabled = state.shareText.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.card_share_button))
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun String?.orPlaceholder(): String = this?.takeIf { it.isNotBlank() } ?: "—"

private fun formatHorizontal(horizontal: Horizontal?): String {
    if (horizontal == null) return "—"
    val alt = formatDegree(horizontal.altDeg)
    val az = formatDegree(horizontal.azDeg)
    return "$alt / $az"
}

private fun formatEquatorial(eq: Equatorial?): String {
    if (eq == null) return "—"
    val ra = formatDegree(eq.raDeg)
    val dec = formatDegree(eq.decDeg, signed = true)
    return "$ra / $dec"
}

private fun formatDegree(
    value: Double,
    includeDegreeSymbol: Boolean = true,
    signed: Boolean = false,
): String {
    val locale = Locale.getDefault()
    val pattern = if (signed) "%+.1f" else "%.1f"
    val text = String.format(locale, pattern, value)
    return if (includeDegreeSymbol) "$text°" else text
}

private fun formatWindow(window: CardBestWindow): String? {
    val formatter =
        DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.getDefault())
            .withZone(ZoneId.systemDefault())
    val start = window.start?.let { formatter.format(it) }
    val end = window.end?.let { formatter.format(it) }
    return when {
        start != null && end != null -> "$start – $end"
        start != null -> start
        end != null -> end
        else -> null
    }
}

@Composable
private fun typeLabel(
    type: CardObjectType,
    body: String?,
): String {
    val base =
        when (type) {
            CardObjectType.STAR -> stringResource(id = R.string.card_type_star)
            CardObjectType.PLANET -> stringResource(id = R.string.card_type_planet)
            CardObjectType.MOON -> stringResource(id = R.string.card_type_moon)
            CardObjectType.CONST -> stringResource(id = R.string.card_type_constellation)
        }
    return when (type) {
        CardObjectType.PLANET, CardObjectType.MOON -> {
            val suffix = body?.takeIf { it.isNotBlank() } ?: return base
            "$base — $suffix"
        }
        else -> base
    }
}
