package dev.pointtosky.mobile.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.pointtosky.mobile.R

private val LOCATION_PERMISSIONS =
    arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: MobileSettingsState,
    onMirrorChanged: (Boolean) -> Unit,
    onArChanged: (Boolean) -> Unit,
    onLocationModeChanged: (LocationMode) -> Unit,
    onRedactPayloadsChanged: (Boolean) -> Unit,
    onOpenPolicy: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var cameraGranted by remember { mutableStateOf(isCameraPermissionGranted(context)) }
    var locationGranted by remember { mutableStateOf(isLocationPermissionGranted(context)) }

    val cameraLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            cameraGranted = granted
        }

    val locationLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            locationGranted = result.values.any { it }
        }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    cameraGranted = isCameraPermissionGranted(context)
                    locationGranted = isLocationPermissionGranted(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(Unit) {
        cameraGranted = isCameraPermissionGranted(context)
        locationGranted = isLocationPermissionGranted(context)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .padding(paddingValues)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SettingsToggleRow(
                    title = stringResource(id = R.string.settings_mirror_title),
                    description = stringResource(id = R.string.settings_mirror_desc),
                    checked = state.mirrorEnabled,
                    onCheckedChange = onMirrorChanged,
                )
                SettingsToggleRow(
                    title = stringResource(id = R.string.settings_ar_title),
                    description = stringResource(id = R.string.settings_ar_desc),
                    checked = state.arEnabled,
                    onCheckedChange = onArChanged,
                )
                SettingsToggleRow(
                    title = stringResource(id = R.string.settings_privacy_redact_title),
                    description = stringResource(id = R.string.settings_privacy_redact_desc),
                    checked = state.redactPayloads,
                    onCheckedChange = onRedactPayloadsChanged,
                )
            }

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(id = R.string.settings_location_mode_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                LocationModeOption(
                    title = stringResource(id = R.string.settings_location_mode_auto),
                    description = stringResource(id = R.string.settings_location_mode_auto_desc),
                    selected = state.locationMode == LocationMode.AUTO,
                    onSelect = { onLocationModeChanged(LocationMode.AUTO) },
                )
                LocationModeOption(
                    title = stringResource(id = R.string.settings_location_mode_manual),
                    description = stringResource(id = R.string.settings_location_mode_manual_desc),
                    selected = state.locationMode == LocationMode.MANUAL,
                    onSelect = { onLocationModeChanged(LocationMode.MANUAL) },
                )
            }

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(id = R.string.settings_permissions_header),
                    style = MaterialTheme.typography.titleMedium,
                )
                PermissionRow(
                    title = stringResource(id = R.string.settings_permission_camera),
                    granted = cameraGranted,
                    onRequest = { cameraLauncher.launch(Manifest.permission.CAMERA) },
                )
                PermissionRow(
                    title = stringResource(id = R.string.settings_permission_location),
                    granted = locationGranted,
                    onRequest = { locationLauncher.launch(LOCATION_PERMISSIONS) },
                )
            }

            HorizontalDivider(Modifier, DividerDefaults.Thickness, DividerDefaults.color)

            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = stringResource(id = R.string.settings_disclaimer),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(id = R.string.settings_policy_link),
                    style =
                        MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.primary,
                            textDecoration = TextDecoration.Underline,
                        ),
                    modifier = Modifier.clickable(onClick = onOpenPolicy),
                )
            }
        }
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
            )
        }
        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LocationModeOption(
    title: String,
    description: String,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onSelect)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(start = 8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PermissionRow(
    title: String,
    granted: Boolean,
    onRequest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall)
            Text(
                text =
                    if (granted) {
                        stringResource(id = R.string.settings_permission_granted)
                    } else {
                        stringResource(id = R.string.settings_permission_not_granted)
                    },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onRequest) {
            Text(text = stringResource(id = R.string.settings_permission_request))
        }
    }
}

private fun isCameraPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun isLocationPermissionGranted(context: Context): Boolean =
    LOCATION_PERMISSIONS.any { permission ->
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }
