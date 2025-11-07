package dev.pointtosky.wear.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.ScalingLazyColumn
import dev.pointtosky.core.time.SystemTimeSource
import dev.pointtosky.core.time.ZoneRepo
import dev.pointtosky.wear.R
import dev.pointtosky.wear.tile.tonight.TonightTileDebug
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.collections.ArrayDeque
import kotlinx.coroutines.flow.collect

@Composable
fun TimeDebugScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val timeSource = remember { SystemTimeSource() }
    val zoneRepo = remember(context.applicationContext) { ZoneRepo(context.applicationContext) }
    TimeDebugContent(
        onBack = onBack,
        timeSource = timeSource,
        zoneRepo = zoneRepo,
        modifier = modifier
    )
}

@Composable
private fun TimeDebugContent(
    onBack: () -> Unit,
    timeSource: SystemTimeSource,
    zoneRepo: ZoneRepo,
    modifier: Modifier = Modifier,
) {
    val zoneId by zoneRepo.zoneFlow.collectAsState(initial = zoneRepo.current())
    val periodMs by timeSource.periodMsFlow.collectAsState()
    val tickMetrics by rememberTickMetrics(timeSource)
    val tileDebugInfo by TonightTileDebug.state.collectAsState(initial = null)

    val utcFormatter = remember { DateTimeFormatter.ISO_INSTANT }
    val localFormatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US) }

    val instant = tickMetrics.instant
    val utcText = utcFormatter.format(instant)
    val localDateTime = instant.atZone(zoneId)
    val localText = localDateTime.format(localFormatter)
    val offset = localDateTime.offset
    val offsetText = String.format(Locale.US, "%s (%d с)", offset.id, offset.totalSeconds)
    val avgPeriodText = tickMetrics.avgPeriodMs?.let { String.format(Locale.US, "%.1f мс", it) } ?: "—"
    val frequencyText = tickMetrics.frequencyHz?.let { String.format(Locale.US, "%.3f Гц", it) } ?: "—"
    val currentPeriodText = String.format(Locale.US, "%d мс", periodMs)

    val tileDebugText = tileDebugInfo?.let { info ->
        val localInstant = info.generatedAt.atZone(zoneId).format(localFormatter)
        val topTarget = info.topTargetTitle ?: stringResource(id = R.string.tile_debug_target_unknown)
        stringResource(id = R.string.tile_debug_last_generation, localInstant, topTarget)
    } ?: stringResource(id = R.string.tile_debug_last_generation_unknown)

    ScalingLazyColumn(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(text = stringResource(id = R.string.time_debug_label), style = MaterialTheme.typography.title3)
        }
        item { Text(text = "UTC: $utcText") }
        item { Text(text = "Local: $localText") }
        item { Text(text = "ZoneId: ${zoneId.id}") }
        item { Text(text = "Offset: $offsetText") }
        item { Text(text = "Avg Δt: $avgPeriodText") }
        item { Text(text = "Avg Hz: $frequencyText") }
        item { Text(text = "Period: $currentPeriodText") }
        item { Text(text = tileDebugText, style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center) }
        item {
            Button(
                onClick = {
                    val next = if (periodMs <= FAST_PERIOD_MS) SLOW_PERIOD_MS else FAST_PERIOD_MS
                    timeSource.updatePeriod(next)
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.primaryButtonColors()
            ) {
                Text(text = stringResource(id = R.string.time_debug_toggle))
            }
        }
        item {
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.secondaryButtonColors()
            ) {
                Text(text = stringResource(id = R.string.time_debug_back))
            }
        }
    }
}

@Composable
private fun rememberTickMetrics(timeSource: SystemTimeSource): State<TickMetrics> {
    return produceState(
        initialValue = TickMetrics(timeSource.now(), null, null),
        key1 = timeSource
    ) {
        val samples = ArrayDeque<Long>()
        timeSource.ticks.collect { instant ->
            val timestamp = instant.toEpochMilli()
            samples.addLast(timestamp)
            while (samples.size > SAMPLE_WINDOW_SIZE) {
                samples.removeFirst()
            }
            val avgPeriod = if (samples.size >= 2) {
                (samples.last() - samples.first()).toDouble() / (samples.size - 1)
            } else {
                null
            }
            val frequency = avgPeriod?.takeIf { it > 0 }?.let { 1_000.0 / it }
            value = TickMetrics(instant, avgPeriod, frequency)
        }
    }
}

private data class TickMetrics(
    val instant: Instant,
    val avgPeriodMs: Double?,
    val frequencyHz: Double?,
)

private const val FAST_PERIOD_MS = 1_000L
private const val SLOW_PERIOD_MS = 5_000L
private const val SAMPLE_WINDOW_SIZE = 6
