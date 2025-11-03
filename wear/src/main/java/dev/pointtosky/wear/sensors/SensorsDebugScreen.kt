package dev.pointtosky.wear.sensors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import dev.pointtosky.wear.R
import dev.pointtosky.wear.sensors.orientation.OrientationFrame
import dev.pointtosky.wear.sensors.orientation.OrientationZero
import dev.pointtosky.wear.sensors.orientation.ScreenRotation

@Composable
fun SensorsDebugScreen(
    frame: OrientationFrame?,
    zero: OrientationZero,
    screenRotation: ScreenRotation,
    frameRate: Float?,
    isSensorActive: Boolean,
    onScreenRotationSelected: (ScreenRotation) -> Unit,
    onNavigateToCalibrate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        state = listState,
    ) {
        item {
            Text(
                text = stringResource(id = R.string.sensors_debug_title),
                style = MaterialTheme.typography.title3,
            )
        }
        item {
            SensorStatusRow(
                isSensorActive = isSensorActive,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        item {
            val frameRateText = frameRate?.let {
                stringResource(id = R.string.current_frame_rate_label, it)
            } ?: stringResource(id = R.string.current_frame_rate_unknown_label)
            Text(
                text = frameRateText,
                style = MaterialTheme.typography.body2,
            )
        }
        item {
            Text(
                text = frame?.azimuthDeg?.let { stringResource(R.string.current_azimuth_label, it) }
                    ?: stringResource(R.string.current_azimuth_unknown_label),
                style = MaterialTheme.typography.body2,
            )
        }
        item {
            Text(
                text = frame?.pitchDeg?.let { stringResource(R.string.current_pitch_label, it) }
                    ?: stringResource(R.string.value_not_available),
                style = MaterialTheme.typography.body2,
            )
        }
        item {
            Text(
                text = frame?.rollDeg?.let { stringResource(R.string.current_roll_label, it) }
                    ?: stringResource(R.string.value_not_available),
                style = MaterialTheme.typography.body2,
            )
        }
        item {
            val accuracyText = frame?.accuracy?.let { accuracy ->
                stringResource(id = orientationAccuracyStringRes(accuracy))
            } ?: stringResource(id = R.string.value_not_available)
            Text(
                text = stringResource(R.string.current_accuracy_label, accuracyText),
                style = MaterialTheme.typography.body2,
            )
        }
        item {
            Text(
                text = stringResource(id = R.string.zero_offset_label, zero.azimuthOffsetDeg),
                style = MaterialTheme.typography.body2,
            )
        }
        item {
            Text(
                text = stringResource(id = R.string.screen_rotation_label),
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        items(ScreenRotation.entries) { rotation ->
            val colors = if (rotation == screenRotation) {
                ChipDefaults.primaryChipColors()
            } else {
                ChipDefaults.secondaryChipColors()
            }
            Chip(
                onClick = { onScreenRotationSelected(rotation) },
                label = {
                    Text(
                        text = stringResource(R.string.screen_rotation_option, rotation.degrees),
                        style = MaterialTheme.typography.body2,
                    )
                },
                colors = colors,
            )
        }
        item {
            Chip(
                onClick = onNavigateToCalibrate,
                label = { Text(text = stringResource(id = R.string.sensors_calibrate_label)) },
                colors = ChipDefaults.primaryChipColors(),
            )
        }
    }
}

@Composable
private fun SensorStatusRow(
    isSensorActive: Boolean,
    modifier: Modifier = Modifier,
) {
    val statusText = stringResource(
        id = if (isSensorActive) {
            R.string.sensor_active_label
        } else {
            R.string.sensor_inactive_label
        },
    )
    val icon = if (isSensorActive) {
        Icons.Filled.PlayArrow
    } else {
        Icons.Filled.Pause
    }
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = statusText,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colors.primary,
        )
        Text(
            text = statusText,
            style = MaterialTheme.typography.body2,
        )
    }
}
