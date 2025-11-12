package dev.pointtosky.wear.sensors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.pointtosky.wear.R
import dev.pointtosky.wear.sensors.orientation.OrientationAccuracy

@Composable
fun SensorsCalibrateScreen(
    azimuthDeg: Float?,
    accuracy: OrientationAccuracy?,
    onSetZero: (Float) -> Unit,
    onResetZero: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accuracyText =
        accuracy?.let { stringResource(id = orientationAccuracyStringRes(it)) }
            ?: stringResource(id = R.string.value_not_available)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = stringResource(id = R.string.sensors_calibrate_title),
            style = MaterialTheme.typography.title3,
        )
        Text(
            text =
                azimuthDeg?.let { stringResource(R.string.current_azimuth_label, it) }
                    ?: stringResource(R.string.current_azimuth_unknown_label),
            style = MaterialTheme.typography.body2,
        )
        Text(
            text = stringResource(R.string.current_accuracy_label, accuracyText),
            style = MaterialTheme.typography.body2,
        )
        Text(
            text = stringResource(id = R.string.sensors_calibrate_hint),
            style = MaterialTheme.typography.caption2,
        )
        Button(
            onClick = { azimuthDeg?.let { onSetZero(it) } },
            enabled = azimuthDeg != null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.primaryButtonColors(),
        ) {
            Text(text = stringResource(id = R.string.set_zero_label))
        }
        Button(
            onClick = onResetZero,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.secondaryButtonColors(),
        ) {
            Text(text = stringResource(id = R.string.reset_zero_label))
        }
    }
}
