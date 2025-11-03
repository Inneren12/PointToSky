package dev.pointtosky.wear.logs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pointtosky.core.common.logs.LOG_TAIL_DEFAULT_LINES
import dev.pointtosky.core.common.logs.LogSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class LogsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WatchLogsRepository.getInstance(application)

    private val _logs = MutableStateFlow<List<LogSummary>>(emptyList())
    val logs: StateFlow<List<LogSummary>> = _logs.asStateFlow()

    private val _tailContent = MutableStateFlow<List<String>>(emptyList())
    val tailContent: StateFlow<List<String>> = _tailContent.asStateFlow()

    private val _tailFileName = MutableStateFlow<String?>(null)
    val tailFileName: StateFlow<String?> = _tailFileName.asStateFlow()

    init {
        viewModelScope.launch {
            repository.refresh()
        }
        viewModelScope.launch {
            repository.logs.collect { summaries ->
                _logs.value = summaries
            }
        }
    }

    fun onRefresh() {
        repository.refresh()
    }

    fun onViewTail(summary: LogSummary) {
        viewModelScope.launch {
            _tailFileName.value = summary.name
            _tailContent.value = repository.readTail(summary, LOG_TAIL_DEFAULT_LINES)
        }
    }

    fun onDismissTail() {
        _tailFileName.value = null
        _tailContent.value = emptyList()
    }

    fun onDelete(summary: LogSummary) {
        repository.delete(summary)
    }

    fun onSendToPhone(summary: LogSummary) {
        repository.sendToPhone(summary)
    }
}
