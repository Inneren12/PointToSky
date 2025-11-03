package dev.pointtosky.wear.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.material.Alert
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.Card
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.rememberScalingLazyListState
import androidx.wear.compose.material.ScalingLazyColumn
import androidx.wear.compose.material.items
import dev.pointtosky.core.common.logs.LOG_RETENTION_MAX_BYTES
import dev.pointtosky.core.common.logs.LOG_RETENTION_MAX_FILES
import dev.pointtosky.core.common.logs.LogSummary
import dev.pointtosky.wear.R

@Composable
fun LogsScreen(viewModel: LogsViewModel) {
    val logs by viewModel.logs.collectAsStateWithLifecycle()
    val tailFileName by viewModel.tailFileName.collectAsStateWithLifecycle()
    val tailLines by viewModel.tailContent.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.onRefresh()
    }

    LogsScreen(
        logs = logs,
        onViewTail = viewModel::onViewTail,
        onDelete = viewModel::onDelete,
        onSendToPhone = viewModel::onSendToPhone,
        onDismissTail = viewModel::onDismissTail,
        tailFileName = tailFileName,
        tailLines = tailLines,
    )
}

@Composable
fun LogsScreen(
    logs: List<LogSummary>,
    onViewTail: (LogSummary) -> Unit,
    onDelete: (LogSummary) -> Unit,
    onSendToPhone: (LogSummary) -> Unit,
    onDismissTail: () -> Unit,
    tailFileName: String?,
    tailLines: List<String>,
) {
    val listState = rememberScalingLazyListState()
    Scaffold(
        timeText = { TimeText() }
    ) {
        ScalingLazyColumn(
            modifier = Modifier.padding(8.dp),
            state = listState,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                Text(
                    text = stringResource(
                        id = R.string.logs_retention_summary,
                        LOG_RETENTION_MAX_FILES,
                        LOG_RETENTION_MAX_BYTES / (1024 * 1024)
                    ),
                    style = MaterialTheme.typography.body2,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            if (logs.isEmpty()) {
                item {
                    Text(
                        text = stringResource(id = R.string.logs_empty),
                        style = MaterialTheme.typography.body1,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            } else {
                items(logs, key = { it.name }) { summary ->
                    LogCard(
                        summary = summary,
                        onViewTail = onViewTail,
                        onDelete = onDelete,
                        onSendToPhone = onSendToPhone,
                    )
                }
            }
        }
    }

    if (tailFileName != null) {
        Alert(
            onDismissRequest = onDismissTail,
            title = { Text(text = stringResource(id = R.string.logs_tail_title, tailFileName!!)) },
            positiveButton = {
                Button(onClick = onDismissTail) {
                    Text(text = stringResource(id = android.R.string.ok))
                }
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                tailLines.forEach { line ->
                    Text(text = line, style = MaterialTheme.typography.body2)
                }
            }
        }
    }
}

@Composable
private fun LogCard(
    summary: LogSummary,
    onViewTail: (LogSummary) -> Unit,
    onDelete: (LogSummary) -> Unit,
    onSendToPhone: (LogSummary) -> Unit,
) {
    Card(
        onClick = { onViewTail(summary) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = summary.name,
                style = MaterialTheme.typography.body1,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = buildString {
                    append(formatSize(summary.sizeBytes))
                    append(" • ")
                    append(formatDate(summary.lastModifiedMillis))
                    if (summary.isCompressed) {
                        append(" • gz")
                    }
                },
                style = MaterialTheme.typography.caption
            )
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(onClick = { onSendToPhone(summary) }) {
                    Text(text = stringResource(id = R.string.logs_send_to_phone))
                }
                Button(onClick = { onDelete(summary) }) {
                    Text(text = stringResource(id = R.string.logs_action_delete))
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    val units = arrayOf("B", "KiB", "MiB", "GiB")
    var size = bytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024
        unitIndex++
    }
    return String.format("%.1f %s", size, units[unitIndex])
}

private fun formatDate(timestamp: Long): String {
    val formatter = java.text.DateFormat.getDateTimeInstance()
    return formatter.format(java.util.Date(timestamp))
}
