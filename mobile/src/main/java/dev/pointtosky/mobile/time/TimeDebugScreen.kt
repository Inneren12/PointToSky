package dev.pointtosky.mobile.time

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.pointtosky.core.time.SystemTimeSource
import dev.pointtosky.core.time.ZoneRepo
import dev.pointtosky.mobile.R
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.collections.ArrayDeque

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
        modifier = modifier,
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

    val utcFormatter = remember { DateTimeFormatter.ISO_INSTANT }
    val localFormatter = remember { DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss", Locale.getDefault()) }

    val instant = tickMetrics.instant
    val utcText = utcFormatter.format(instant)
    val localDateTime = instant.atZone(zoneId)
    val localText = localDateTime.format(localFormatter)
    val offset = localDateTime.offset
    val offsetText = String.format(Locale.US, "%s (%d с)", offset.id, offset.totalSeconds)

    val avgPeriodText = tickMetrics.avgPeriodMs?.let { String.format(Locale.US, "%.1f мс", it) } ?: "—"
    val frequencyText = tickMetrics.frequencyHz?.let { String.format(Locale.US, "%.3f Гц", it) } ?: "—"
    val currentPeriodText = String.format(Locale.US, "%d мс", periodMs)

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(id = R.string.time_debug),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(text = "UTC: $utcText")
        Text(text = "Local: $localText")
        Text(text = "ZoneId: ${zoneId.id}")
        Text(text = "Offset: $offsetText")
        Text(text = "Avg Δt: $avgPeriodText")
        Text(text = "Avg Hz: $frequencyText")
        Text(text = "Period: $currentPeriodText")
        Button(onClick = {
            val next = if (periodMs <= FAST_PERIOD_MS) SLOW_PERIOD_MS else FAST_PERIOD_MS
            timeSource.updatePeriod(next)
        }) {
            Text(text = stringResource(id = R.string.time_debug_toggle))
        }
        Button(onClick = onBack) {
            Text(text = stringResource(id = R.string.time_debug_back))
        }
    }
}

@Composable
private fun rememberTickMetrics(timeSource: SystemTimeSource): State<TickMetrics> {
    return produceState(
        initialValue = TickMetrics(timeSource.now(), null, null),
        key1 = timeSource,
    ) {
        val samples = ArrayDeque<Long>()
        timeSource.ticks.collect { instant ->
            val timestamp = instant.toEpochMilli()
            samples.addLast(timestamp)
            while (samples.size > SAMPLE_WINDOW_SIZE) {
                samples.removeFirst()
            }
            val avgPeriod =
                if (samples.size >= 2) {
                    (samples.last() - samples.first()).toDouble() / (samples.size - 1)
                } else {
                    null
                }
            val frequency =
                avgPeriod?.takeIf { it > 0 }
                    ?.let { 1_000.0 / it }
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
