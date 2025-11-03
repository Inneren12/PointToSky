package dev.pointtosky.mobile.logs

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.pointtosky.core.common.logs.LOG_RETENTION_MAX_BYTES
import dev.pointtosky.core.common.logs.LOG_RETENTION_MAX_FILES
import dev.pointtosky.core.common.logs.LOG_TAIL_DEFAULT_LINES
import dev.pointtosky.core.common.logs.WatchTransferEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LogsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = PhoneLogsRepository.getInstance(application)
    private val watchInteractor = WatchLogsInteractor(application)

    private val _shareTargets = MutableStateFlow<ShareTarget?>(null)
    val shareTargets: StateFlow<ShareTarget?> = _shareTargets

    private val _tailContent = MutableStateFlow<List<String>>(emptyList())
    val tailContent: StateFlow<List<String>> = _tailContent

    private val _tailFileName = MutableStateFlow<String?>(null)
    val tailFileName: StateFlow<String?> = _tailFileName

    private val _uiState: StateFlow<LogsUiState>

    private data class AuxState(
        val isRequestInProgress: Boolean = false,
        val requestError: String? = null,
        val progressFileName: String? = null,
    )

    private val auxState = MutableStateFlow(AuxState())

    private val retentionPolicy = RetentionPolicyUi(
        maxFiles = LOG_RETENTION_MAX_FILES,
        maxBytes = LOG_RETENTION_MAX_BYTES
    )

    private var activeRequest: String? = null

    init {
        _uiState = combine(
            repository.logs,
            WatchAnnouncementsRepository.remoteLogs,
            auxState
        ) { localEntries, remoteEntries, aux ->
            LogsUiState(
                phoneLogs = localEntries.filter { it.source == LogFileSource.PHONE },
                watchLogs = localEntries.filter { it.source == LogFileSource.WATCH_DOWNLOADED },
                remoteWatchLogs = remoteEntries,
                retentionPolicy = retentionPolicy,
                isRequestInProgress = aux.isRequestInProgress,
                requestError = aux.requestError,
                progressFileName = aux.progressFileName,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogsUiState.empty(retentionPolicy))

        viewModelScope.launch {
            WatchTransferStatusBus.events.collect { event ->
                if (event is WatchTransferEvent.Completed) {
                    val isInProgress = activeRequest != null && activeRequest == event.fileName
                    if (!isInProgress) return@collect
                    activeRequest = null
                    auxState.update {
                        it.copy(
                            isRequestInProgress = false,
                            requestError = event.message?.takeIf { !event.success },
                            progressFileName = null,
                        )
                    }
                }
            }
        }
    }

    fun uiState(): StateFlow<LogsUiState> = _uiState

    fun refresh() {
        repository.refresh()
    }

    fun onViewTail(entry: LogFileEntry) {
        viewModelScope.launch {
            val lines = repository.readTail(entry, LOG_TAIL_DEFAULT_LINES)
            _tailFileName.value = entry.summary.name
            _tailContent.value = lines
        }
    }

    fun onDismissTail() {
        _tailFileName.value = null
        _tailContent.value = emptyList()
    }

    fun onDelete(entry: LogFileEntry) {
        viewModelScope.launch {
            repository.delete(entry)
        }
    }

    fun onShare(entry: LogFileEntry) {
        viewModelScope.launch {
            _shareTargets.value = repository.buildShareTarget(entry)
        }
    }

    fun onShareConsumed() {
        _shareTargets.value = null
    }

    fun onDismissError() {
        auxState.update {
            it.copy(requestError = null)
        }
    }

    fun onRequestWatchFile(entry: RemoteWatchLogEntry) {
        if (activeRequest != null) return
        viewModelScope.launch {
            activeRequest = entry.summary.name
            auxState.update {
                it.copy(
                    isRequestInProgress = true,
                    requestError = null,
                    progressFileName = entry.summary.name,
                )
            }
            val result = watchInteractor.requestFile(entry.summary.name, entry.summary.sizeBytes)
            if (result.isFailure) {
                activeRequest = null
                auxState.update {
                    it.copy(
                        isRequestInProgress = false,
                        requestError = result.exceptionOrNull()?.message,
                        progressFileName = null,
                    )
                }
            }
        }
    }
}
