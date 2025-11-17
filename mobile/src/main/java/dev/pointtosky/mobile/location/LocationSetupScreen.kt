
package dev.pointtosky.mobile.location

import android.Manifest
import android.app.Activity
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.mobile.R
import dev.pointtosky.mobile.location.share.PhoneLocationBridge
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationSetupScreen(
    locationPrefs: LocationPrefs,
    shareState: PhoneLocationBridge.PhoneLocationBridgeState,
    onShareToggle: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permissionState = rememberLocationPermissionUiState()
    val manualPoint by locationPrefs.manualPointFlow.collectAsState(initial = null)
    BackHandler(onBack = onBack)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(id = R.string.location_setup_title)) },
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
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(id = R.string.location_setup_description),
                style = MaterialTheme.typography.bodyLarge,
            )
            PermissionSection(permissionState)
            ShareSection(shareState = shareState, onShareToggle = onShareToggle)
            ManualSection(manualPoint = manualPoint, locationPrefs = locationPrefs)
        }
    }
}

@Composable
private fun PermissionSection(state: PermissionUiState) {
    if (state.granted) {
        Text(
            text = stringResource(id = R.string.location_setup_permission_granted),
            style = MaterialTheme.typography.bodyMedium,
        )
    } else {
        Button(onClick = state.requestPermission) {
            Text(text = stringResource(id = R.string.location_setup_request_permission))
        }
        if (state.shouldOpenSettings) {
            OutlinedButton(onClick = state.openSettings) {
                Text(text = stringResource(id = R.string.location_setup_open_settings))
            }
        }
    }
}

@Composable
private fun ShareSection(
    shareState: PhoneLocationBridge.PhoneLocationBridgeState,
    onShareToggle: (Boolean) -> Unit,
) {
    val dateFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(id = R.string.location_share_toggle),
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = shareState.shareEnabled,
                onCheckedChange = onShareToggle,
            )
        }

        Text(
            text = stringResource(id = R.string.location_share_description),
            style = MaterialTheme.typography.bodySmall,
        )

        shareState.lastRequest?.let { request ->
            val timeString = dateFormat.format(Date(request.timestampMs))
            val ttlString =
                stringResource(
                    id = R.string.location_share_ttl_seconds,
                    (request.freshTtlMs / 1000L).coerceAtLeast(0L).toInt(),
                )
            Text(
                text =
                    stringResource(
                        id = R.string.location_share_last_request,
                        timeString,
                        ttlString,
                    ),
                style = MaterialTheme.typography.bodySmall,
            )
        }

        shareState.lastResponse?.let { response ->
            val timeString = dateFormat.format(Date(response.timestampMs))
            val message =
                when (response.status) {
                    PhoneLocationBridge.ResponseStatus.SUCCESS -> {
                        val accuracyText =
                            response.accuracyM?.let {
                                stringResource(id = R.string.location_share_accuracy, it)
                            }
                        val info =
                            listOfNotNull(response.provider, accuracyText)
                                .joinToString(separator = " • ")
                                .ifEmpty { response.provider.orEmpty() }
                        stringResource(
                            id = R.string.location_share_status_success,
                            timeString,
                            info.ifEmpty { "—" },
                        )
                    }
                    PhoneLocationBridge.ResponseStatus.SHARING_DISABLED ->
                        stringResource(
                            id = R.string.location_share_status_disabled,
                        )
                    PhoneLocationBridge.ResponseStatus.PERMISSION_DENIED ->
                        stringResource(
                            id = R.string.location_share_status_permission_denied,
                        )
                    PhoneLocationBridge.ResponseStatus.LOCATION_UNAVAILABLE ->
                        stringResource(
                            id = R.string.location_share_status_unavailable,
                        )
                    PhoneLocationBridge.ResponseStatus.SEND_FAILED ->
                        stringResource(
                            id = R.string.location_share_status_send_failed,
                        )
                }
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ManualSection(
    manualPoint: GeoPoint?,
    locationPrefs: LocationPrefs,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var manualEnabled by remember { mutableStateOf(manualPoint != null) }
    var latitudeInput by remember { mutableStateOf("") }
    var longitudeInput by remember { mutableStateOf("") }
    var showValidationErrors by remember { mutableStateOf(false) }

    LaunchedEffect(manualPoint) {
        manualEnabled = manualPoint != null
        if (manualPoint != null) {
            latitudeInput = manualPoint.latDeg.formatCoordinate()
            longitudeInput = manualPoint.lonDeg.formatCoordinate()
        } else {
            latitudeInput = ""
            longitudeInput = ""
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(id = R.string.location_setup_manual_toggle),
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = manualEnabled,
                onCheckedChange = { enabled ->
                    manualEnabled = enabled
                    showValidationErrors = false
                    if (!enabled) {
                        coroutineScope.launch { locationPrefs.setManual(null) }
                    }
                },
            )
        }

        if (!manualEnabled) {
            Text(
                text = stringResource(id = R.string.location_setup_device_fallback_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        if (manualEnabled) {
            Text(
                text = stringResource(id = R.string.location_setup_manual_hint),
                style = MaterialTheme.typography.bodySmall,
            )

            val latitudeValidation = validateLatitude(latitudeInput)
            val longitudeValidation = validateLongitude(longitudeInput)
            val latError = latitudeValidation.errorRes?.takeIf { showValidationErrors }
            val lonError = longitudeValidation.errorRes?.takeIf { showValidationErrors }

            OutlinedTextField(
                value = latitudeInput,
                onValueChange = { latitudeInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = stringResource(id = R.string.location_setup_lat_label)) },
                placeholder = { Text(text = stringResource(id = R.string.location_setup_lat_hint)) },
                isError = latError != null,
                supportingText = {
                    if (latError != null) {
                        Text(text = stringResource(id = latError))
                    }
                },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Next,
                    ),
            )

            OutlinedTextField(
                value = longitudeInput,
                onValueChange = { longitudeInput = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(text = stringResource(id = R.string.location_setup_lon_label)) },
                placeholder = { Text(text = stringResource(id = R.string.location_setup_lon_hint)) },
                isError = lonError != null,
                supportingText = {
                    if (lonError != null) {
                        Text(text = stringResource(id = lonError))
                    }
                },
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done,
                    ),
            )

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
                        Toast
                            .makeText(
                                context,
                                context.getString(R.string.location_setup_saved_toast),
                                Toast.LENGTH_SHORT,
                            ).show()
                        showValidationErrors = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.location_setup_save))
            }
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
private fun rememberLocationPermissionUiState(): PermissionUiState {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
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
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    context.startActivity(intent)
}

private fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
