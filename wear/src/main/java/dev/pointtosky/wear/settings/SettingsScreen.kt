package dev.pointtosky.wear.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import dev.pointtosky.wear.R
import kotlinx.coroutines.launch

@Composable
fun SettingsRoute(settings: AimIdentifySettingsDataStore, onBack: () -> Unit, modifier: Modifier = Modifier) {
    SettingsScreen(
        state = rememberSettingsState(settings),
        settings = settings,
        onBack = onBack,
        modifier = modifier,
    )
}

data class SettingsState(
    val azTol: Double,
    val altTol: Double,
    val holdMs: Long,
    val hapticEnabled: Boolean,
    val magLimit: Double,
    val radiusDeg: Double,
    val tileMirror: Boolean,
)

@Composable
private fun rememberSettingsState(settings: AimIdentifySettingsDataStore): SettingsState {
    val az by settings.aimAzTolFlow.collectAsStateWithLifecycle(initialValue = 3.0)
    val alt by settings.aimAltTolFlow.collectAsStateWithLifecycle(initialValue = 4.0)
    val hold by settings.aimHoldMsFlow.collectAsStateWithLifecycle(initialValue = 1200L)
    val hap by settings.aimHapticEnabledFlow.collectAsStateWithLifecycle(initialValue = true)
    val mag by settings.identifyMagLimitFlow.collectAsStateWithLifecycle(initialValue = 5.5)
    val rad by settings.identifyRadiusDegFlow.collectAsStateWithLifecycle(initialValue = 5.0)
    val mirror by settings.tileMirroringEnabledFlow.collectAsStateWithLifecycle(initialValue = false)
    return SettingsState(az, alt, hold, hap, mag, rad, mirror)
}

@Composable
fun SettingsScreen(state: SettingsState, settings: AimIdentifySettingsDataStore, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()

    ScalingLazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                text = stringResource(id = R.string.settings_title),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Aim: azTol (±0.5°)
        item {
            Text(
                text = stringResource(id = R.string.aim_az_tol_label, state.azTol),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            settings.setAimAzTol(
                                (state.azTol - 0.5).coerceIn(0.5, 10.0),
                            )
                        }
                    },
                ) { Text(text = "−") }
                Button(
                    onClick = {
                        scope.launch {
                            settings.setAimAzTol(
                                (state.azTol + 0.5).coerceIn(0.5, 10.0),
                            )
                        }
                    },
                ) { Text(text = "+") }
            }
        }
        // Aim: altTol (±0.5°)
        item {
            Text(
                text = stringResource(id = R.string.aim_alt_tol_label, state.altTol),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            settings.setAimAltTol(
                                (state.altTol - 0.5).coerceIn(0.5, 10.0),
                            )
                        }
                    },
                ) { Text(text = "−") }
                Button(
                    onClick = {
                        scope.launch {
                            settings.setAimAltTol(
                                (state.altTol + 0.5).coerceIn(0.5, 10.0),
                            )
                        }
                    },
                ) { Text(text = "+") }
            }
        }
        // Aim: holdMs (±100 ms)
        item {
            Text(
                text = stringResource(id = R.string.aim_hold_ms_label, state.holdMs),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            val v = (state.holdMs - 100L).coerceIn(200L, 3000L)
                            settings.setAimHoldMs(v)
                        }
                    },
                ) { Text(text = "−") }
                Button(
                    onClick = {
                        scope.launch {
                            val v = (state.holdMs + 100L).coerceIn(200L, 3000L)
                            settings.setAimHoldMs(v)
                        }
                    },
                ) { Text(text = "+") }
            }
        }
        // Aim: haptic toggle
        item {
            ToggleChip(
                checked = state.hapticEnabled,
                onCheckedChange = { enabled -> scope.launch { settings.setAimHapticEnabled(enabled) } },
                label = { Text(text = stringResource(id = R.string.aim_haptic_label)) },
                toggleControl = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Identify: radius (±0.5°)
        item {
            Text(
                text = stringResource(id = R.string.identify_radius_label, state.radiusDeg),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            settings.setIdentifyRadiusDeg(
                                (state.radiusDeg - 0.5).coerceIn(0.5, 10.0),
                            )
                        }
                    },
                ) { Text(text = "−") }
                Button(
                    onClick = {
                        scope.launch {
                            settings.setIdentifyRadiusDeg(
                                (state.radiusDeg + 0.5).coerceIn(0.5, 10.0),
                            )
                        }
                    },
                ) { Text(text = "+") }
            }
        }
        // Identify: mag limit (±0.5)
        item {
            Text(
                text = stringResource(id = R.string.identify_mag_limit_label, state.magLimit),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Button(
                    onClick = {
                        scope.launch {
                            settings.setIdentifyMagLimit(
                                (state.magLimit - 0.5).coerceIn(-1.0, 9.0),
                            )
                        }
                    },
                ) { Text(text = "−") }
                Button(
                    onClick = {
                        scope.launch {
                            settings.setIdentifyMagLimit(
                                (state.magLimit + 0.5).coerceIn(-1.0, 9.0),
                            )
                        }
                    },
                ) { Text(text = "+") }
            }
        }
        // Tiles: Mirroring toggle
        item {
            ToggleChip(
                checked = state.tileMirror,
                onCheckedChange = { enabled -> scope.launch { settings.setTileMirroringEnabled(enabled) } },
                label = { Text(text = "Mirroring to phone") },
                toggleControl = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Tiles: Mirroring toggle
        item {
            ToggleChip(
                checked = state.tileMirror,
                onCheckedChange = { enabled -> scope.launch { settings.setTileMirroringEnabled(enabled) } },
                label = { Text(text = stringResource(id = R.string.settings_tile_mirroring_title)) },
                toggleControl = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Back
        item {
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.secondaryButtonColors(),
            ) {
                Text(text = stringResource(id = R.string.settings_back))
            }
        }
    }
}
