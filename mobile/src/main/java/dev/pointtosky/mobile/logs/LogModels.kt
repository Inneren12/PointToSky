package dev.pointtosky.mobile.logs

import dev.pointtosky.core.common.logs.LogSummary

enum class LogFileSource {
    PHONE,
    WATCH_DOWNLOADED,
}

data class LogFileEntry(
    val summary: LogSummary,
    val source: LogFileSource,
)

data class RemoteWatchLogEntry(
    val summary: LogSummary,
)

data class RetentionPolicyUi(
    val maxFiles: Int,
    val maxBytes: Long,
)

data class LogsUiState(
    val phoneLogs: List<LogFileEntry> = emptyList(),
    val watchLogs: List<LogFileEntry> = emptyList(),
    val remoteWatchLogs: List<RemoteWatchLogEntry> = emptyList(),
    val retentionPolicy: RetentionPolicyUi,
    val isRequestInProgress: Boolean = false,
    val requestError: String? = null,
    val progressFileName: String? = null,
) {
    companion object {
        fun empty(policy: RetentionPolicyUi) = LogsUiState(
            retentionPolicy = policy
        )
    }
}

data class ShareTarget(
    val uriString: String,
    val mimeType: String,
    val title: String,
)
