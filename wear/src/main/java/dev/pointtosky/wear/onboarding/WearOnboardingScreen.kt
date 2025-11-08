package dev.pointtosky.wear.onboarding

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.rememberScalingLazyListState
import dev.pointtosky.wear.R
import android.content.pm.PackageManager

@Composable
fun WearOnboardingScreen(
    onComplete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var acknowledged by remember { mutableStateOf(false) }
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val locationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        locationGranted = granted
    }

    LaunchedEffect(Unit) {
        locationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 16.dp)
    ) {
        item {
            Text(
                text = stringResource(id = R.string.onboarding_title),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Text(
                text = stringResource(id = R.string.onboarding_description),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            ToggleChip(
                checked = acknowledged,
                onCheckedChange = { acknowledged = it },
                label = { Text(text = stringResource(id = R.string.onboarding_checkbox)) },
                toggleControl = {},
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text(
                text = stringResource(id = R.string.onboarding_location_label),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            if (locationGranted) {
                Text(
                    text = stringResource(id = R.string.onboarding_location_granted),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Button(
                    onClick = { locationLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION) },
                    modifier = Modifier.fillMaxWidth(),
                    ) {
                    Text(text = stringResource(id = R.string.onboarding_location_button))
                }
            }
        }
        item {
            Button(
                onClick = onComplete,
                enabled = acknowledged,
                modifier = Modifier.fillMaxWidth(),
                ) {
                Text(text = stringResource(id = R.string.onboarding_continue))
            }
        }
    }
}
