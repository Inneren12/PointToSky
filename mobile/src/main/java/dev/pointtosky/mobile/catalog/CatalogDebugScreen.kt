package dev.pointtosky.mobile.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pointtosky.core.catalog.runtime.debug.CatalogDebugUiState
import dev.pointtosky.core.catalog.runtime.debug.CatalogDebugViewModel
import dev.pointtosky.core.catalog.runtime.debug.CatalogDebugViewModelFactory
import dev.pointtosky.core.catalog.runtime.debug.ProbeResultUi
import dev.pointtosky.core.catalog.runtime.debug.formatBytes
import dev.pointtosky.core.catalog.runtime.debug.formatDegrees
import dev.pointtosky.core.catalog.runtime.debug.formatMagnitude
import dev.pointtosky.mobile.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CatalogDebugRoute(
    factory: CatalogDebugViewModelFactory,
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
) {
    val viewModel: CatalogDebugViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    CatalogDebugScreen(
        state = state,
        onRaChange = viewModel::updateRa,
        onDecChange = viewModel::updateDec,
        onRadiusChange = viewModel::updateRadius,
        onMagLimitChange = viewModel::updateMagLimit,
        onRunProbe = viewModel::runProbe,
        onRunSelfTest = viewModel::runSelfTest,
        onBack = onBack,
        modifier = modifier,
    )
}

@Composable
fun CatalogDebugScreen(
    state: CatalogDebugUiState,
    onRaChange: (String) -> Unit,
    onDecChange: (String) -> Unit,
    onRadiusChange: (String) -> Unit,
    onMagLimitChange: (String) -> Unit,
    onRunProbe: () -> Unit,
    onRunSelfTest: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = stringResource(id = R.string.catalog_debug_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(text = buildStarInfo(state), style = MaterialTheme.typography.bodyMedium)
                Text(text = buildConstellationInfo(state), style = MaterialTheme.typography.bodyMedium)
            }
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(),
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(id = R.string.catalog_debug_probe_header),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedTextField(
                        value = state.probeForm.ra,
                        onValueChange = onRaChange,
                        label = { Text(stringResource(id = R.string.catalog_debug_probe_ra)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.probeForm.dec,
                        onValueChange = onDecChange,
                        label = { Text(stringResource(id = R.string.catalog_debug_probe_dec)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.probeForm.radius,
                        onValueChange = onRadiusChange,
                        label = { Text(stringResource(id = R.string.catalog_debug_probe_radius)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = state.probeForm.magLimit,
                        onValueChange = onMagLimitChange,
                        label = { Text(stringResource(id = R.string.catalog_debug_probe_mag)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val err = state.probeError
                    if (!err.isNullOrBlank()) {
                        Text(
                            text = err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = onRunProbe, modifier = Modifier.fillMaxWidth()) {
                        Text(text = stringResource(id = R.string.catalog_debug_probe_action))
                    }
                }
            }
        }
        if (state.probeResults.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = stringResource(id = R.string.catalog_debug_probe_results_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        state.probeResults.forEach { result ->
                            Text(text = formatProbeResult(result), style = MaterialTheme.typography.bodyMedium)
                        }
                        state.lastProbeTimestamp?.let { timestamp ->
                            Text(
                                text =
                                    stringResource(
                                        id = R.string.catalog_debug_probe_timestamp,
                                        formatTimestamp(timestamp),
                                    ),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
        item {
            Button(onClick = onRunSelfTest, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(id = R.string.catalog_debug_self_test_action))
            }
        }
        if (state.selfTestResults.isNotEmpty()) {
            items(state.selfTestResults) { result ->
                val prefix = if (result.passed) "✅" else "❌"
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(text = "$prefix ${result.name}", style = MaterialTheme.typography.titleSmall)
                        Text(text = result.detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(id = R.string.catalog_debug_back))
            }
        }
    }
}
private fun buildStarInfo(state: CatalogDebugUiState): String {
    val diagnostics = state.diagnostics
    val metadata = diagnostics.starMetadata
    return if (metadata != null) {
        // показываем то, что реально есть в AstroCatalogStats
        "Stars: ${metadata.starCount} • constellations ${metadata.constellationCount} " +
            "• asterisms ${metadata.asterismCount} • art overlays ${metadata.artOverlayCount} " +
            "• load ${diagnostics.starLoadDurationMs} ms"
    } else {
        "Stars: fallback catalog • load ${diagnostics.starLoadDurationMs} ms"
    }
}

private fun buildConstellationInfo(state: CatalogDebugUiState): String {
    val diagnostics = state.diagnostics
    val metadata = diagnostics.boundaryMetadata
    return if (metadata != null) {
        // у Metadata гарантированно есть recordCount, его и используем
        "Constellations: ${metadata.recordCount} • load ${diagnostics.boundaryLoadDurationMs} ms"
    } else {
        "Constellations: fallback boundaries • load ${diagnostics.boundaryLoadDurationMs} ms"
    }
}

private fun formatProbeResult(result: ProbeResultUi): String {
    val mag = formatMagnitude(result.magnitude)
    val separation = formatDegrees(result.separationDeg)
    val constellation = result.constellation ?: "—"
    return "${result.index}. ${result.label} (ID ${result.id}, m=$mag, Δ=$separation, $constellation)"
}

private fun formatTimestamp(timestampMs: Long): String {
    val instant = Instant.ofEpochMilli(timestampMs)
    val formatter =
        DateTimeFormatter
            .ofPattern("HH:mm:ss")
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}
