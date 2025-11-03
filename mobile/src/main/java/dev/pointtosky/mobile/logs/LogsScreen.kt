package dev.pointtosky.mobile.logs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.pointtosky.mobile.R

@Composable
fun LogsRoute(viewModel: LogsViewModel) {
    val uiState by viewModel.uiState().collectAsStateWithLifecycle()
    val tailFileName by viewModel.tailFileName.collectAsStateWithLifecycle()
    val tailContent by viewModel.tailContent.collectAsStateWithLifecycle()

    LogsScreen(
        uiState = uiState,
        onViewTail = viewModel::onViewTail,
        onShare = viewModel::onShare,
        onDelete = viewModel::onDelete,
        onDismissTail = viewModel::onDismissTail,
        onDismissError = viewModel::onDismissError,
        onRequestWatchFile = viewModel::onRequestWatchFile,
        tailFileName = tailFileName,
        tailLines = tailContent,
    )
}

@Composable
fun LogsScreen(
    uiState: LogsUiState,
    onViewTail: (LogFileEntry) -> Unit,
    onShare: (LogFileEntry) -> Unit,
    onDelete: (LogFileEntry) -> Unit,
    onDismissTail: () -> Unit,
    onRequestWatchFile: (RemoteWatchLogEntry) -> Unit,
    onDismissError: () -> Unit,
    tailFileName: String?,
    tailLines: List<String>,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(text = stringResource(id = R.string.logs_title)) })
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text(
                    text = stringResource(
                        id = R.string.logs_retention_summary,
                        uiState.retentionPolicy.maxFiles,
                        uiState.retentionPolicy.maxBytes / (1024 * 1024)
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (uiState.phoneLogs.isNotEmpty()) {
                item {
                    SectionHeader(text = stringResource(id = R.string.logs_section_phone))
                }
                items(uiState.phoneLogs, key = { it.summary.name }) { entry ->
                    LogRow(
                        entry = entry,
                        onViewTail = onViewTail,
                        onShare = onShare,
                        onDelete = onDelete,
                    )
                }
            }

            if (uiState.watchLogs.isNotEmpty()) {
                item {
                    SectionHeader(text = stringResource(id = R.string.logs_section_watch_downloaded))
                }
                items(uiState.watchLogs, key = { it.summary.name }) { entry ->
                    LogRow(
                        entry = entry,
                        onViewTail = onViewTail,
                        onShare = onShare,
                        onDelete = onDelete,
                    )
                }
            }

            if (uiState.remoteWatchLogs.isNotEmpty()) {
                item {
                    SectionHeader(text = stringResource(id = R.string.logs_section_watch_remote))
                }
                items(uiState.remoteWatchLogs, key = { it.summary.name }) { entry ->
                    RemoteLogRow(
                        entry = entry,
                        isRequestInProgress = uiState.isRequestInProgress && uiState.progressFileName == entry.summary.name,
                        onRequest = onRequestWatchFile,
                    )
                }
            }

            if (uiState.phoneLogs.isEmpty() && uiState.watchLogs.isEmpty() && uiState.remoteWatchLogs.isEmpty()) {
                item {
                    Text(
                        text = stringResource(id = R.string.logs_empty),
                        modifier = Modifier.padding(top = 24.dp)
                    )
                }
            }
        }
    }

    if (tailFileName != null) {
        TailDialog(
            fileName = tailFileName,
            lines = tailLines,
            onDismiss = onDismissTail
        )
    }

    if (uiState.requestError != null) {
        AlertDialog(
            onDismissRequest = onDismissError,
            title = { Text(text = stringResource(id = R.string.logs_request_failed_title)) },
            text = { Text(text = uiState.requestError) },
            confirmButton = {
                TextButton(onClick = onDismissError) {
                    Text(text = stringResource(id = android.R.string.ok))
                }
            }
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
    )
}

@Composable
private fun LogRow(
    entry: LogFileEntry,
    onViewTail: (LogFileEntry) -> Unit,
    onShare: (LogFileEntry) -> Unit,
    onDelete: (LogFileEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.summary.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = entry.formattedSize(), style = MaterialTheme.typography.labelMedium)
                    Text(text = entry.formattedDate(), style = MaterialTheme.typography.labelMedium)
                    if (entry.summary.isCompressed) {
                        Text(text = stringResource(id = R.string.logs_compressed_indicator), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            IconButton(onClick = { onViewTail(entry) }) {
                Icon(imageVector = Icons.Filled.Visibility, contentDescription = stringResource(id = R.string.logs_action_view_tail))
            }
            IconButton(onClick = { onShare(entry) }) {
                Icon(imageVector = Icons.Filled.Share, contentDescription = stringResource(id = R.string.logs_action_share))
            }
            IconButton(onClick = { onDelete(entry) }) {
                Icon(imageVector = Icons.Filled.Delete, contentDescription = stringResource(id = R.string.logs_action_delete))
            }
        }
        Divider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun RemoteLogRow(
    entry: RemoteWatchLogEntry,
    isRequestInProgress: Boolean,
    onRequest: (RemoteWatchLogEntry) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = entry.summary.name,
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = formatSize(entry.summary.sizeBytes), style = MaterialTheme.typography.labelMedium)
                    Text(text = formatDate(entry.summary.lastModifiedMillis), style = MaterialTheme.typography.labelMedium)
                    if (entry.summary.isCompressed) {
                        Text(text = stringResource(id = R.string.logs_compressed_indicator), style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (isRequestInProgress) {
                CircularProgressIndicator(modifier = Modifier.padding(8.dp))
            } else {
                Button(onClick = { onRequest(entry) }) {
                    Text(text = stringResource(id = R.string.logs_action_request))
                }
            }
        }
        Divider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun TailDialog(
    fileName: String,
    lines: List<String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.logs_tail_title, fileName)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = 4.dp)
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                lines.forEach { line ->
                    Text(text = line, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = android.R.string.ok))
            }
        }
    )
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
