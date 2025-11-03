package dev.pointtosky.wear.sensors

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SensorsOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.pointtosky.core.logging.FrameTraceMode
import dev.pointtosky.core.logging.LogWriterStats
import dev.pointtosky.wear.R
import dev.pointtosky.wear.sensors.orientation.OrientationAccuracy
import dev.pointtosky.wear.sensors.orientation.OrientationFrame
import dev.pointtosky.wear.sensors.orientation.OrientationSource
import dev.pointtosky.wear.sensors.orientation.OrientationZero
import dev.pointtosky.wear.sensors.orientation.ScreenRotation
import dev.pointtosky.wear.sensors.util.FrameRateAverager

@Composable
fun SensorsDebugScreen(
    frame: OrientationFrame?,
    zero: OrientationZero,
    screenRotation: ScreenRotation,
    frameTraceMode: FrameTraceMode,
    source: OrientationSource,
    writerStats: LogWriterStats,
    isSensorActive: Boolean,
    onFrameTraceModeSelected: (FrameTraceMode) -> Unit,
    onScreenRotationSelected: (ScreenRotation) -> Unit,
    onNavigateToCalibrate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberScalingLazyListState()
    val frameRateAverager = remember { FrameRateAverager(windowDurationMillis = 3_000L) }
    var averagedFps by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(isSensorActive) {
        if (!isSensorActive) {
            frameRateAverager.reset()
            averagedFps = null
        }
    }

    LaunchedEffect(frame?.timestampNanos) {
        val timestamp = frame?.timestampNanos
        if (timestamp != null) {
            averagedFps = frameRateAverager.add(timestamp)
        }
    }

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
                fps = averagedFps,
                accuracy = frame?.accuracy,
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
            val accuracy = frame?.accuracy
            if (accuracy != null) {
                val accuracyText = stringResource(id = orientationAccuracyStringRes(accuracy))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.current_accuracy_label, accuracyText),
                        style = MaterialTheme.typography.body2,
                    )
                    AccuracyIndicator(accuracy = accuracy)
                    if (accuracy == OrientationAccuracy.LOW || accuracy == OrientationAccuracy.UNRELIABLE) {
                        Text(
                            text = stringResource(id = R.string.accuracy_hint_figure_eight),
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.secondary,
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(
                        id = R.string.current_accuracy_label,
                        stringResource(id = R.string.value_not_available),
                    ),
                    style = MaterialTheme.typography.body2,
                )
            }
        }
        item {
            val sourceText = when (source) {
                OrientationSource.ROTATION_VECTOR -> stringResource(id = R.string.source_rotation_vector)
                OrientationSource.ACCEL_MAG -> stringResource(id = R.string.source_accel_mag)
            }
            Text(
                text = stringResource(id = R.string.current_source_label, sourceText),
                style = MaterialTheme.typography.body2,
            )
        }
        item {
            Text(
                text = stringResource(
                    id = R.string.writer_stats_label,
                    writerStats.queuedEvents,
                    writerStats.droppedEvents,
                ),
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
            Text(
                text = stringResource(id = R.string.frame_trace_label),
                style = MaterialTheme.typography.caption1,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        items(FrameTraceMode.entries) { mode ->
            val colors = if (mode == frameTraceMode) {
                ChipDefaults.primaryChipColors()
            } else {
                ChipDefaults.secondaryChipColors()
            }
            Chip(
                onClick = { onFrameTraceModeSelected(mode) },
                label = {
                    Text(
                        text = when (mode) {
                            FrameTraceMode.OFF -> stringResource(id = R.string.frame_trace_off)
                            FrameTraceMode.SUMMARY_1HZ -> stringResource(id = R.string.frame_trace_summary)
                            FrameTraceMode.FULL_15HZ -> stringResource(id = R.string.frame_trace_full)
                        },
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
    fps: Float?,
    accuracy: OrientationAccuracy?,
) {
    val statusText = if (isSensorActive) {
        stringResource(id = R.string.sensor_status_active)
    } else {
        stringResource(id = R.string.sensor_status_inactive)
    }
    val icon = if (isSensorActive) {
        Icons.Rounded.Sensors
    } else {
        Icons.Rounded.SensorsOff
    }
    val contentDescription = if (isSensorActive) {
        stringResource(id = R.string.sensor_status_icon_active)
    } else {
        stringResource(id = R.string.sensor_status_icon_inactive)
    }
    val fpsText = fps?.let { value -> stringResource(id = R.string.current_fps_label, value) }
        ?: stringResource(id = R.string.value_not_available)
    val accuracyLabel = accuracy?.let { level ->
        stringResource(id = orientationAccuracyStringRes(level))
    } ?: stringResource(id = R.string.value_not_available)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (isSensorActive) MaterialTheme.colors.primary else MaterialTheme.colors.onSurface.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = statusText, style = MaterialTheme.typography.body2)
            Text(
                text = stringResource(id = R.string.current_fps_prefix, fpsText),
                style = MaterialTheme.typography.caption1,
            )
            Text(
                text = stringResource(id = R.string.current_accuracy_label, accuracyLabel),
                style = MaterialTheme.typography.caption1,
            )
        }
    }
}

@Composable
private fun AccuracyIndicator(
    accuracy: OrientationAccuracy,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(id = orientationAccuracyStringRes(accuracy))
    val (background, content) = accuracyColors(accuracy)
    Text(
        text = label,
        style = MaterialTheme.typography.caption2,
        color = content,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

@Composable
private fun accuracyColors(accuracy: OrientationAccuracy): Pair<Color, Color> {
    val background = when (accuracy) {
        OrientationAccuracy.HIGH -> colorResource(id = R.color.accuracy_high)
        OrientationAccuracy.MEDIUM -> colorResource(id = R.color.accuracy_medium)
        OrientationAccuracy.LOW -> colorResource(id = R.color.accuracy_low)
        OrientationAccuracy.UNRELIABLE -> colorResource(id = R.color.accuracy_unreliable)
    }
    val content = when (accuracy) {
        OrientationAccuracy.HIGH, OrientationAccuracy.UNRELIABLE -> Color.White
        OrientationAccuracy.MEDIUM, OrientationAccuracy.LOW -> Color.Black
    }
    return background to content
}
