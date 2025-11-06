package dev.pointtosky.wear.catalogdebug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.ScalingLazyListState
import androidx.wear.compose.material.Text
import androidx.compose.material3.TextField
import androidx.wear.compose.material.rememberScalingLazyListState
import dev.pointtosky.core.catalog.runtime.debug.CatalogDebugUiState
import dev.pointtosky.core.catalog.runtime.debug.CatalogDebugViewModel
import dev.pointtosky.core.catalog.runtime.debug.CatalogDebugViewModelFactory
import dev.pointtosky.core.catalog.runtime.debug.ProbeResultUi
import dev.pointtosky.core.catalog.runtime.debug.formatBytes
import dev.pointtosky.core.catalog.runtime.debug.formatDegrees
import dev.pointtosky.core.catalog.runtime.debug.formatMagnitude
import dev.pointtosky.wear.R
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import androidx.compose.ui.text.input.KeyboardType

@Composable
fun CatalogDebugRoute(
    factory: CatalogDebugViewModelFactory,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: CatalogDebugViewModel = viewModel(factory = factory)
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()
    CatalogDebugScreen(
        state = state,
        listState = listState,
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
    listState: ScalingLazyListState,
    onRaChange: (String) -> Unit,
    onDecChange: (String) -> Unit,
    onRadiusChange: (String) -> Unit,
    onMagLimitChange: (String) -> Unit,
    onRunProbe: () -> Unit,
    onRunSelfTest: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val starInfo = buildStarInfo(state)
    val constellationInfo = buildConstellationInfo(state)
    ScalingLazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                text = stringResource(id = R.string.catalog_debug_title),
                style = MaterialTheme.typography.title3,
                fontWeight = FontWeight.Bold,
            )
        }
        item {
            Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                Text(text = starInfo, style = MaterialTheme.typography.caption1)
                Spacer(Modifier.height(4.dp))
                Text(text = constellationInfo, style = MaterialTheme.typography.caption1)
            }
        }
        item {
            Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                Text(text = stringResource(id = R.string.catalog_debug_probe_header), style = MaterialTheme.typography.caption2)
                TextField(
                    value = state.probeForm.ra,
                    onValueChange = onRaChange,
                    label = { Text(stringResource(id = R.string.catalog_debug_probe_ra)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                TextField(
                    value = state.probeForm.dec,
                    onValueChange = onDecChange,
                    label = { Text(stringResource(id = R.string.catalog_debug_probe_dec)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                TextField(
                    value = state.probeForm.radius,
                    onValueChange = onRadiusChange,
                    label = { Text(stringResource(id = R.string.catalog_debug_probe_radius)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(4.dp))
                TextField(
                    value = state.probeForm.magLimit,
                    onValueChange = onMagLimitChange,
                    label = { Text(stringResource(id = R.string.catalog_debug_probe_mag)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                val err = state.probeError
                if (!err.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = err,
                        color = Color.Red,
                        style = MaterialTheme.typography.caption2,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick = onRunProbe,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = stringResource(id = R.string.catalog_debug_probe_action))
                }
            }
        }
        if (state.probeResults.isNotEmpty()) {
            item {
                Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(id = R.string.catalog_debug_probe_results_title),
                        style = MaterialTheme.typography.caption2,
                    )
                    state.probeResults.forEach { result ->
                        Text(
                            text = formatProbeResult(result),
                            style = MaterialTheme.typography.body2,
                        )
                    }
                    state.lastProbeTimestamp?.let { timestamp ->
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = stringResource(
                                id = R.string.catalog_debug_probe_timestamp,
                                formatTimestamp(timestamp),
                            ),
                            style = MaterialTheme.typography.caption2,
                        )
                    }
                }
            }
        }
        item {
            Button(
                onClick = onRunSelfTest,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.catalog_debug_self_test_action))
            }
        }
        if (state.selfTestResults.isNotEmpty()) {
            item {
                Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(id = R.string.catalog_debug_self_test_title),
                        style = MaterialTheme.typography.caption2,
                    )
                    state.selfTestResults.forEach { result ->
                        val prefix = if (result.passed) "✅" else "❌"
                        Text(
                            text = "$prefix ${result.name}: ${result.detail}",
                            style = MaterialTheme.typography.body2,
                        )
                    }
                }
            }
        }
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.wear.compose.material.ButtonDefaults.secondaryButtonColors(),
            ) {
                Text(text = stringResource(id = R.string.catalog_debug_back))
            }
        }
    }
}

private fun buildStarInfo(state: CatalogDebugUiState): String {
    val diagnostics = state.diagnostics
    val metadata = diagnostics.starMetadata
    return if (metadata != null) {
        val crcHex = metadata.payloadCrc32.toString(16).uppercase(Locale.ROOT)
        val size = formatBytes(metadata.sizeBytes)
        "Stars: ${metadata.starCount} • ${size} • CRC ${crcHex} • load ${diagnostics.starLoadDurationMs} ms"
    } else {
        "Stars: fallback catalog • load ${diagnostics.starLoadDurationMs} ms"
    }
}

private fun buildConstellationInfo(state: CatalogDebugUiState): String {
    val diagnostics = state.diagnostics
    val metadata = diagnostics.boundaryMetadata
    return if (metadata != null) {
        val crcHex = metadata.payloadCrc32.toString(16).uppercase(Locale.ROOT)
        val size = formatBytes(metadata.sizeBytes)
        "Constellations: ${metadata.recordCount} • ${size} • CRC ${crcHex} • load ${diagnostics.boundaryLoadDurationMs} ms"
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
    val formatter = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withLocale(Locale.getDefault())
        .withZone(ZoneId.systemDefault())
    return formatter.format(instant)
}
