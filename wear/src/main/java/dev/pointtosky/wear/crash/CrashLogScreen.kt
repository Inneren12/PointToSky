package dev.pointtosky.wear.crash

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.wear.R
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun CrashLogRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val application = remember(context) { context.applicationContext as Application }
    val viewModel: CrashLogViewModel = viewModel(factory = CrashLogViewModelFactory(application))
    val state by viewModel.state.collectAsStateWithLifecycle()
    CrashLogScreen(
        state = state,
        onBack = onBack,
        onClear = viewModel::clearLogs,
        onCreateZip = viewModel::createZip,
        onDismissMessage = viewModel::dismissMessage,
        modifier = modifier,
    )
}

@Composable
fun CrashLogScreen(
    state: CrashLogUiState,
    onBack: () -> Unit,
    onClear: () -> Unit,
    onCreateZip: () -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val zoneId = remember { ZoneId.systemDefault() }
    val formatter = remember { DateTimeFormatter.ofPattern("HH:mm:ss", Locale.getDefault()) }
    val scope = rememberCoroutineScope()
    val lastCrash = state.lastCrash
    val timestampText = remember(lastCrash) {
        lastCrash?.timestamp?.atZone(zoneId)?.let { formatter.format(it) }
    }

    ScalingLazyColumn(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(text = stringResource(id = R.string.crash_logs_title), style = MaterialTheme.typography.title3)
        }
        item {
            Text(
                text = stringResource(id = R.string.crash_logs_instruction),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
            )
        }
        if (state.statusMessage != null || state.errorMessage != null) {
            item {
                val text = (state.statusMessage ?: state.errorMessage).orEmpty()
                val color = if (state.errorMessage != null) {
                    MaterialTheme.colors.error
                } else {
                    MaterialTheme.colors.primary
                }
                Text(
                    text = text,
                    color = color,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Button(
                    onClick = onDismissMessage,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.secondaryButtonColors(),
                ) {
                    Text(text = stringResource(id = R.string.crash_logs_message_dismiss))
                }
            }
        }
        if (state.lastZip != null) {
            item {
                Text(
                    text = stringResource(id = R.string.crash_logs_zip_path, state.lastZip.absolutePath),
                    style = MaterialTheme.typography.caption1,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (lastCrash != null) {
            item {
                Text(text = stringResource(id = R.string.crash_logs_last_crash), style = MaterialTheme.typography.body2)
            }
            if (timestampText != null) {
                item {
                    Text(text = stringResource(id = R.string.crash_logs_last_crash_time, timestampText))
                }
            }
            item {
                Text(
                    text = stringResource(
                        id = R.string.crash_logs_last_crash_thread,
                        lastCrash.threadName,
                        lastCrash.threadId,
                    ),
                )
            }
            item {
                Text(text = stringResource(id = R.string.crash_logs_last_crash_type, lastCrash.exceptionType))
            }
            lastCrash.message?.takeIf { it.isNotBlank() }?.let { message ->
                item { Text(text = stringResource(id = R.string.crash_logs_last_crash_message, message)) }
            }
            item { Text(text = stringResource(id = R.string.crash_logs_stacktrace_label)) }
            item {
                Text(
                    text = lastCrash.stacktrace,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Start,
                )
            }
        } else {
            item {
                Text(
                    text = stringResource(id = R.string.crash_logs_empty),
                    textAlign = TextAlign.Center,
                )
            }
        }
        item {
            Button(
                onClick = {
                    scope.launch {
                        LogBus.flushAndSync()
                        onCreateZip()
                    }
                },
                enabled = state.hasLogs && !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.primaryButtonColors(),
            ) {
                Text(text = stringResource(id = R.string.crash_logs_create_zip))
            }
        }
        item {
            Button(
                onClick = onClear,
                enabled = state.hasLogs && !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.secondaryButtonColors(),
            ) {
                Text(text = stringResource(id = R.string.crash_logs_clear))
            }
        }
        item {
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.secondaryButtonColors(),
            ) {
                Text(text = stringResource(id = R.string.crash_logs_back))
            }
        }
    }
}
