package dev.pointtosky.mobile.logs

import android.content.Context
import androidx.core.content.FileProvider
import dev.pointtosky.core.common.logs.LOG_RETENTION_MAX_BYTES
import dev.pointtosky.core.common.logs.LOG_RETENTION_MAX_FILES
import dev.pointtosky.core.common.logs.LOGS_DIRECTORY
import dev.pointtosky.core.common.logs.LOGS_FROM_WATCH_DIRECTORY
import dev.pointtosky.core.common.logs.LogSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class PhoneLogsRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _logs = MutableStateFlow(emptyList<LogFileEntry>())
    val logs: StateFlow<List<LogFileEntry>> = _logs.asStateFlow()

    private val logsDir: File = File(context.filesDir, LOGS_DIRECTORY)
    private val watchLogsDir: File = File(logsDir, LOGS_FROM_WATCH_DIRECTORY)

    init {
        LogsFileUtils.ensureDirectory(logsDir)
        LogsFileUtils.ensureDirectory(watchLogsDir)
        refresh()
    }

    fun refresh() {
        scope.launch {
            val entries = buildList {
                LogsFileUtils.enforceRetention(logsDir, LOG_RETENTION_MAX_FILES, LOG_RETENTION_MAX_BYTES)
                LogsFileUtils.enforceRetention(watchLogsDir, LOG_RETENTION_MAX_FILES, LOG_RETENTION_MAX_BYTES)

                addAll(readEntries(logsDir, LogFileSource.PHONE))
                addAll(readEntries(watchLogsDir, LogFileSource.WATCH_DOWNLOADED))
            }.sortedByDescending { it.summary.lastModifiedMillis }
            _logs.value = entries
        }
    }

    private fun readEntries(directory: File, source: LogFileSource): List<LogFileEntry> {
        val files = if (directory.exists()) directory.walkTopDown().filter { it.isFile }.toList() else emptyList()
        return files.map { file ->
            LogFileEntry(
                summary = LogSummary(
                    name = file.relativeTo(directory).path.replace("\\", "/"),
                    sizeBytes = file.length(),
                    lastModifiedMillis = file.lastModified(),
                    isCompressed = file.extension.equals("gz", ignoreCase = true)
                ),
                source = source
            )
        }
    }

    fun delete(entry: LogFileEntry): Boolean {
        val target = resolve(entry) ?: return false
        val deleted = target.delete()
        if (deleted) {
            refresh()
        }
        return deleted
    }

    fun readTail(entry: LogFileEntry, maxLines: Int): List<String> {
        val target = resolve(entry) ?: return emptyList()
        return LogsFileUtils.readTail(target, maxLines)
    }

    fun buildShareTarget(entry: LogFileEntry): ShareTarget? {
        val target = resolve(entry) ?: return null
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, target)
        val mimeType = if (entry.summary.isCompressed) {
            "application/gzip"
        } else {
            "application/json"
        }
        return ShareTarget(uri.toString(), mimeType, entry.summary.name)
    }

    fun saveWatchFile(name: String, data: ByteArray): File {
        LogsFileUtils.ensureDirectory(watchLogsDir)
        val target = File(watchLogsDir, name)
        target.parentFile?.let { LogsFileUtils.ensureDirectory(it) }
        FileOutputStream(target).use { fos ->
            fos.write(data)
        }
        refresh()
        return target
    }

    fun resolve(entry: LogFileEntry): File? {
        val base = when (entry.source) {
            LogFileSource.PHONE -> logsDir
            LogFileSource.WATCH_DOWNLOADED -> watchLogsDir
        }
        return resolveRelative(base, entry.summary.name)
    }

    private fun resolveRelative(base: File, relative: String): File? {
        val target = File(base, relative)
        return if (target.canonicalPath.startsWith(base.canonicalPath)) target else null
    }

    fun createWatchLogsDir(): File = watchLogsDir

    fun getLogsDir(): File = logsDir

    companion object {
        @Volatile
        private var instance: PhoneLogsRepository? = null

        fun getInstance(context: Context): PhoneLogsRepository {
            return instance ?: synchronized(this) {
                instance ?: PhoneLogsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}

fun LogFileEntry.formattedSize(): String {
    val units = arrayOf("B", "KiB", "MiB", "GiB")
    var size = summary.sizeBytes.toDouble()
    var unitIndex = 0
    while (size >= 1024 && unitIndex < units.lastIndex) {
        size /= 1024
        unitIndex++
    }
    return String.format(Locale.getDefault(), "%.1f %s", size, units[unitIndex])
}

fun LogFileEntry.formattedDate(): String {
    val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM, Locale.getDefault())
    return dateFormat.format(Date(summary.lastModifiedMillis))
}
