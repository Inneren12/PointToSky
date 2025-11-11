package dev.pointtosky.wear.crash

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.pointtosky.core.logging.CrashLogEntry
import dev.pointtosky.core.logging.CrashLogManager
import dev.pointtosky.wear.R
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class CrashLogUiState(
    val lastCrash: CrashLogEntry? = null,
    val hasLogs: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val lastZip: File? = null,
    val isBusy: Boolean = false,
)

class CrashLogViewModel(
    private val application: Application,
    /** Инжектируемый IO-диспетчер (тестируемость, без хардкода). */
    private val io: CoroutineDispatcher = CrashDispatchers.io,
) : ViewModel() {
    private val _state = MutableStateFlow(
        CrashLogUiState(
            lastCrash = runCatching { CrashLogManager.currentLastCrash() }.getOrNull(),
            hasLogs = runCatching { CrashLogManager.hasLogs() }.getOrElse { false },
        ),
    )
    val state: StateFlow<CrashLogUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { CrashLogManager.lastCrashFlow() }
                .getOrNull()
                ?.collect { entry ->
                    _state.update { current ->
                        current.copy(
                            lastCrash = entry,
                            hasLogs = entry != null || runCatching { CrashLogManager.hasLogs() }.getOrElse { false },
                            statusMessage = null,
                            errorMessage = null,
                        )
                    }
                }
        }
    }

    fun clearLogs() {
        viewModelScope.launch(io) {
            _state.update { it.copy(isBusy = true, statusMessage = null, errorMessage = null, lastZip = null) }
            runCatching { CrashLogManager.clear() }
                .onSuccess {
                    val message = application.getString(R.string.crash_logs_cleared)
                    val hasLogs = runCatching { CrashLogManager.hasLogs() }.getOrElse { false }
                    _state.update { current ->
                        current.copy(
                            isBusy = false,
                            hasLogs = hasLogs,
                            statusMessage = message,
                            lastCrash = null,
                        )
                    }
                }
                .onFailure { error ->
                    _state.update { current ->
                        current.copy(
                            isBusy = false,
                            errorMessage =
                            error.localizedMessage ?: application.getString(R.string.crash_logs_operation_failed),
                        )
                    }
                }
        }
    }

    fun createZip() {
        viewModelScope.launch(io) {
            _state.update { it.copy(isBusy = true, statusMessage = null, errorMessage = null) }
            val targetDirectory = File(application.filesDir, "crash")
            val result = runCatching { CrashLogManager.createZip(targetDirectory) }
            val hasLogs = runCatching { CrashLogManager.hasLogs() }.getOrElse { false }
            result.onSuccess { file ->
                if (file != null) {
                    val message = application.getString(R.string.crash_logs_zip_ready, file.absolutePath)
                    _state.update { current ->
                        current.copy(
                            isBusy = false,
                            statusMessage = message,
                            lastZip = file,
                            hasLogs = hasLogs,
                        )
                    }
                } else {
                    val message = application.getString(R.string.crash_logs_zip_unavailable)
                    _state.update { current ->
                        current.copy(
                            isBusy = false,
                            errorMessage = message,
                            hasLogs = hasLogs,
                        )
                    }
                }
            }.onFailure { error ->
                val message = error.localizedMessage ?: application.getString(R.string.crash_logs_zip_unavailable)
                _state.update { current ->
                    current.copy(
                        isBusy = false,
                        errorMessage = message,
                        hasLogs = hasLogs,
                    )
                }
            }
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(statusMessage = null, errorMessage = null) }
    }
}

class CrashLogViewModelFactory(
    private val application: Application,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CrashLogViewModel::class.java)) {
            return CrashLogViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class ${modelClass.name}")
    }
}

// Провайдер дефолтного диспетчера; подавляем линт для внутреннего использования.
private object CrashDispatchers {
    @Suppress("InjectDispatcher")
    val io: CoroutineDispatcher = Dispatchers.IO
}
