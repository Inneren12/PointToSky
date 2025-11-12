package dev.pointtosky.wear.location

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.app.TaskStackBuilder
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import dev.pointtosky.core.location.api.LocationConfig
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.location.model.ProviderType
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.wear.R
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.abs

@Composable
fun LocationSetupScreen(
    locationPrefs: LocationPrefs,
    phoneFix: LocationFix?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val permissionState = rememberPermissionUiState()
    val manualPoint by locationPrefs.manualPointFlow.collectAsState(initial = null)
    val usePhoneFallback by locationPrefs.usePhoneFallbackFlow.collectAsState(initial = false)
    var manualEnabled by remember { mutableStateOf(manualPoint != null) }
    var latitudeInput by remember { mutableStateOf("") }
    var longitudeInput by remember { mutableStateOf("") }
    var showValidationErrors by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(manualPoint) {
        manualEnabled = manualPoint != null
        manualPoint?.let { point ->
            latitudeInput = point.latDeg.formatCoordinate()
            longitudeInput = point.lonDeg.formatCoordinate()
        } ?: run {
            latitudeInput = ""
            longitudeInput = ""
        }
    }

    BackHandler(onBack = onBack)

    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                text = stringResource(id = R.string.location_setup_title),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                text = stringResource(id = R.string.location_setup_description),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (permissionState.granted) {
            item {
                Text(
                    text = stringResource(id = R.string.location_setup_permission_granted),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            item {
                Button(
                    onClick = permissionState.requestPermission,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.primaryButtonColors(),
                ) {
                    Text(text = stringResource(id = R.string.location_setup_request_permission))
                }
            }
            if (permissionState.shouldOpenSettings) {
                item {
                    Chip(
                        onClick = permissionState.openSettings,
                        label = { Text(text = stringResource(id = R.string.location_setup_open_settings)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        item {
            ToggleChip(
                checked = manualEnabled,
                onCheckedChange = { enabled ->
                    manualEnabled = enabled
                    showValidationErrors = false
                    if (!enabled) {
                        coroutineScope.launch { locationPrefs.setManual(null) }
                    }
                },
                label = { Text(text = stringResource(id = R.string.location_setup_manual_toggle)) },
                toggleControl = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (manualEnabled) {
            val latitudeValidation = validateLatitude(latitudeInput)
            val longitudeValidation = validateLongitude(longitudeInput)
            val latError = latitudeValidation.errorRes?.takeIf { showValidationErrors }
            val lonError = longitudeValidation.errorRes?.takeIf { showValidationErrors }

            item {
                Text(
                    text = stringResource(id = R.string.location_setup_manual_hint),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TextField(
                        value = latitudeInput,
                        onValueChange = { latitudeInput = it },
                        label = { Text(text = stringResource(id = R.string.location_setup_lat_label)) },
                        placeholder = { Text(text = stringResource(id = R.string.location_setup_lat_hint)) },
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Next,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (latError != null) {
                        Text(
                            text = stringResource(id = latError),
                            style = MaterialTheme.typography.caption3,
                            color = MaterialTheme.colors.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    TextField(
                        value = longitudeInput,
                        onValueChange = { longitudeInput = it },
                        label = { Text(text = stringResource(id = R.string.location_setup_lon_label)) },
                        placeholder = { Text(text = stringResource(id = R.string.location_setup_lon_hint)) },
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = KeyboardType.Decimal,
                                imeAction = ImeAction.Done,
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (lonError != null) {
                        Text(
                            text = stringResource(id = lonError),
                            style = MaterialTheme.typography.caption3,
                            color = MaterialTheme.colors.error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = {
                        showValidationErrors = true
                        val latResult = validateLatitude(latitudeInput)
                        val lonResult = validateLongitude(longitudeInput)
                        if (latResult.value != null && lonResult.value != null) {
                            coroutineScope.launch {
                                locationPrefs.setManual(
                                    GeoPoint(
                                        latDeg = latResult.value,
                                        lonDeg = lonResult.value,
                                    ),
                                )
                            }
                            Toast.makeText(
                                context,
                                context.getString(R.string.location_setup_saved_toast),
                                Toast.LENGTH_SHORT,
                            ).show()
                            showValidationErrors = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.primaryButtonColors(),
                ) {
                    Text(text = stringResource(id = R.string.location_setup_save))
                }
            }
        }

        item {
            val now = System.currentTimeMillis()
            val isPhoneFresh =
                phoneFix != null &&
                    phoneFix.provider == ProviderType.REMOTE_PHONE &&
                    now - phoneFix.timeMs <= LocationConfig().freshTtlMs
            val sourceLabel =
                if (isPhoneFresh) {
                    stringResource(id = R.string.location_phone_source_phone)
                } else {
                    stringResource(id = R.string.location_phone_source_unknown)
                }
            Text(
                text = stringResource(id = R.string.location_phone_source_label, sourceLabel),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            ToggleChip(
                checked = usePhoneFallback,
                onCheckedChange = { enabled ->
                    coroutineScope.launch { locationPrefs.setUsePhoneFallback(enabled) }
                },
                label = { Text(text = stringResource(id = R.string.location_setup_use_phone_fallback)) },
                toggleControl = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private data class PermissionUiState(
    val granted: Boolean,
    val shouldOpenSettings: Boolean,
    val requestPermission: () -> Unit,
    val openSettings: () -> Unit,
)

@Composable
private fun rememberPermissionUiState(): PermissionUiState {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val activity = remember(context) { context.findActivity() }
    var granted by remember { mutableStateOf(isLocationPermissionGranted(context)) }
    var shouldOpenSettings by remember { mutableStateOf(false) }
    var hasRequested by remember { mutableStateOf(false) }

    val launcher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { result ->
            granted = result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            if (!granted) {
                val rationale =
                    activity?.let {
                        ActivityCompat.shouldShowRequestPermissionRationale(
                            it,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                        )
                    } ?: false
                shouldOpenSettings = !rationale
            } else {
                shouldOpenSettings = false
            }
        }

    DisposableEffect(lifecycleOwner, context) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    val grantedNow = isLocationPermissionGranted(context)
                    granted = grantedNow
                    if (!grantedNow && hasRequested) {
                        val rationale =
                            activity?.let {
                                ActivityCompat.shouldShowRequestPermissionRationale(
                                    it,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                )
                            } ?: false
                        shouldOpenSettings = !rationale
                    }
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    return PermissionUiState(
        granted = granted,
        shouldOpenSettings = shouldOpenSettings,
        requestPermission = {
            hasRequested = true
            shouldOpenSettings = false
            launcher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
        },
        openSettings = { openAppSettings(context) },
    )
}

private fun String.normalizeCoordinate(): Double? = replace(',', '.').toDoubleOrNull()

private data class CoordinateValidation(
    val value: Double?,
    val errorRes: Int?,
)

private fun validateLatitude(input: String): CoordinateValidation {
    val value = input.normalizeCoordinate()
    return when {
        value == null -> CoordinateValidation(null, R.string.location_setup_lat_error_format)
        abs(value) > 90.0 -> CoordinateValidation(null, R.string.location_setup_lat_error_range)
        else -> CoordinateValidation(value, null)
    }
}

private fun validateLongitude(input: String): CoordinateValidation {
    val value = input.normalizeCoordinate()
    return when {
        value == null -> CoordinateValidation(null, R.string.location_setup_lon_error_format)
        abs(value) > 180.0 -> CoordinateValidation(null, R.string.location_setup_lon_error_range)
        else -> CoordinateValidation(value, null)
    }
}

private fun Double.formatCoordinate(): String = String.format(Locale.US, "%.5f", this)

private fun isLocationPermissionGranted(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        }
    val activity = context.findActivity()
    if (activity != null) {
        activity.startActivity(intent)
    } else {
        // безопасный запуск без NEW_TASK/CLEAR_TOP
        val stack = TaskStackBuilder.create(context).addNextIntentWithParentStack(intent)
        val pi =
            stack.getPendingIntent(
                0,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        try {
            pi?.send()
        } catch (_: PendingIntent.CanceledException) {
            // no-op
        }
    }
}

private fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
