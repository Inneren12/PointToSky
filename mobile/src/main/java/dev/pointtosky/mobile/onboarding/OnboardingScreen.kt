@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.pointtosky.mobile.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.pointtosky.mobile.R

@Composable
fun OnboardingScreen(onComplete: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val packageManager = context.packageManager

    var acknowledged by remember { mutableStateOf(false) }
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        locationGranted = granted
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        cameraGranted = granted
    }

    val cameraSupported = remember(packageManager) {
        packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
    }

    LaunchedEffect(Unit) {
        locationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        cameraGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CAMERA,
        ) == PackageManager.PERMISSION_GRANTED
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(title = { Text(text = stringResource(id = R.string.onboarding_title)) })
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(id = R.string.onboarding_description),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = acknowledged,
                    onCheckedChange = { acknowledged = it },
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(id = R.string.onboarding_checkbox),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(id = R.string.onboarding_location_label),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (locationGranted) {
                    Text(
                        text = stringResource(id = R.string.onboarding_location_granted),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Button(onClick = { locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) }) {
                        Text(text = stringResource(id = R.string.onboarding_location_button))
                    }
                    Text(
                        text = stringResource(id = R.string.onboarding_location_denied),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (cameraSupported) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(id = R.string.onboarding_camera_label),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (cameraGranted) {
                        Text(
                            text = stringResource(id = R.string.onboarding_camera_granted),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    } else {
                        Button(onClick = { cameraLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text(text = stringResource(id = R.string.onboarding_camera_button))
                        }
                        Text(
                            text = stringResource(id = R.string.onboarding_camera_denied),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                OutlinedButton(
                    onClick = onComplete,
                    enabled = acknowledged,
                ) {
                    Text(text = stringResource(id = R.string.onboarding_continue))
                }
            }
        }
    }
}
