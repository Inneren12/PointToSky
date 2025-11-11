package dev.pointtosky.mobile.crash

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.pointtosky.core.logging.CrashLogEntry
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.mobile.R
import kotlinx.coroutines.launch
import java.io.File
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
        onShareZip = { file -> CrashLogSharing.shareZip(context, file) },
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
    onShareZip: (File) -> Unit,
    onDismissMessage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val zoneId = remember { ZoneId.systemDefault() }
    val formatter = remember {
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.US)
    }
    val lastCrash = state.lastCrash
    val timestampText = remember(lastCrash) {
        lastCrash?.timestamp?.atZone(zoneId)?.let { formatter.format(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = stringResource(id = R.string.crash_logs_title),
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = stringResource(id = R.string.crash_logs_instruction),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (state.statusMessage != null || state.errorMessage != null) {
            val text = state.statusMessage ?: state.errorMessage ?: ""
            val color = if (state.errorMessage != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
            Text(
                text = text,
                color = color,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismissMessage) {
                Text(text = stringResource(id = R.string.crash_logs_message_dismiss))
            }
        }
        if (state.lastZip != null) {
            Text(
                text = stringResource(id = R.string.crash_logs_zip_path, state.lastZip.absolutePath),
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { onShareZip(state.lastZip) },
                enabled = !state.isBusy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(id = R.string.crash_logs_share_zip))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (lastCrash != null) {
            Text(
                text = stringResource(id = R.string.crash_logs_last_crash),
            )
            timestampText?.let {
                Text(text = stringResource(id = R.string.crash_logs_last_crash_time, it))
            }
            Text(
                text = stringResource(
                    id = R.string.crash_logs_last_crash_thread,
                    lastCrash.threadName,
                    lastCrash.threadId,
                ),
            )
            Text(
                text = stringResource(
                    id = R.string.crash_logs_last_crash_type,
                    lastCrash.exceptionType,
                ),
            )
            lastCrash.message?.let { message ->
                if (message.isNotBlank()) {
                    Text(text = stringResource(id = R.string.crash_logs_last_crash_message, message))
                }
            }
            Text(text = stringResource(id = R.string.crash_logs_stacktrace_label))
            SelectionContainer {
                Text(
                    text = lastCrash.stacktrace,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            Text(
                text = stringResource(id = R.string.crash_logs_empty),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = {
                scope.launch {
                    LogBus.flushAndSync()
                    onCreateZip()
                }
            },
            enabled = state.hasLogs && !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.crash_logs_create_zip))
        }
        OutlinedButton(
            onClick = onClear,
            enabled = state.hasLogs && !state.isBusy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.crash_logs_clear))
        }
        OutlinedButton(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(id = R.string.crash_logs_back))
        }
    }
}

@Preview
@Composable
private fun CrashLogScreenPreview() {
    val entry = CrashLogEntry.from(Thread.currentThread(), RuntimeException("Boom"))
    CrashLogScreen(
        state = CrashLogUiState(
            lastCrash = entry,
            hasLogs = true,
            statusMessage = "ZIP saved",
        ),
        onBack = {},
        onClear = {},
        onCreateZip = {},
        onShareZip = {},
        onDismissMessage = {},
    )
}
