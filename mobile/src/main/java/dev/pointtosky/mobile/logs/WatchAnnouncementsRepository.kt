package dev.pointtosky.mobile.logs

import dev.pointtosky.core.common.logs.LogSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object WatchAnnouncementsRepository {
    private val _remoteLogs = MutableStateFlow(emptyList<RemoteWatchLogEntry>())
    val remoteLogs: StateFlow<List<RemoteWatchLogEntry>> = _remoteLogs.asStateFlow()

    fun update(logs: List<LogSummary>) {
        _remoteLogs.value = logs.sortedByDescending { it.lastModifiedMillis }
            .map { RemoteWatchLogEntry(it) }
    }
}
