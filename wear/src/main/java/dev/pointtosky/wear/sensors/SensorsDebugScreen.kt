package dev.pointtosky.wear.sensors

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.pointtosky.core.logging.FrameTraceMode
import dev.pointtosky.core.logging.LogWriterStats
import dev.pointtosky.wear.R
import dev.pointtosky.wear.sensors.orientation.OrientationFrame
import dev.pointtosky.wear.sensors.orientation.OrientationSource
import dev.pointtosky.wear.sensors.orientation.OrientationZero
import dev.pointtosky.wear.sensors.orientation.ScreenRotation

@Composable
fun SensorsDebugScreen(
    frame: OrientationFrame?,
    zero: OrientationZero,
    screenRotation: ScreenRotation,
    frameTraceMode: FrameTraceMode,
    fps: Float?,
    source: OrientationSource,
    writerStats: LogWriterStats,
    onFrameTraceModeSelected: (FrameTraceMode) -> Unit,
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
            val fpsText = fps?.let { value -> stringResource(id = R.string.current_fps_label, value) }
                ?: stringResource(id = R.string.value_not_available)
            Text(
                text = stringResource(id = R.string.current_fps_prefix, fpsText),
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
