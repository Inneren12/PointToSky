package dev.pointtosky.wear.logs

import android.content.Context
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import dev.pointtosky.core.common.logs.LOG_DATA_ITEM_PREFIX
import dev.pointtosky.core.common.logs.LOG_MESSAGE_ANNOUNCE_PATH
import dev.pointtosky.core.common.logs.LOG_MESSAGE_QUERY_FILE
import dev.pointtosky.core.common.logs.LOG_MESSAGE_QUERY_REASON
import dev.pointtosky.core.common.logs.LOG_MESSAGE_REASON_MISSING
import dev.pointtosky.core.common.logs.LOG_MESSAGE_REASON_TOO_LARGE
import dev.pointtosky.core.common.logs.LOG_MESSAGE_TRANSFER_FAILED_PATH
import dev.pointtosky.core.common.logs.LOG_RETENTION_MAX_BYTES
import dev.pointtosky.core.common.logs.LOG_RETENTION_MAX_FILES
import dev.pointtosky.core.common.logs.LOG_TRANSFER_SIZE_LIMIT_BYTES
import dev.pointtosky.core.common.logs.LOGS_DIRECTORY
import dev.pointtosky.core.common.logs.LogSummary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class WatchLogsRepository private constructor(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val logsDir = File(context.filesDir, LOGS_DIRECTORY)
    private val messageClient = Wearable.getMessageClient(context)
    private val nodeClient = Wearable.getNodeClient(context)
    private val dataClient = Wearable.getDataClient(context)

    private val _logs = MutableStateFlow(emptyList<LogSummary>())
    val logs: StateFlow<List<LogSummary>> = _logs.asStateFlow()

    init {
        ensureDirectory()
        refresh()
    }

    private fun ensureDirectory() {
        if (!logsDir.exists()) {
            logsDir.mkdirs()
        }
    }

    fun refresh() {
        scope.launch {
            LogsFileUtils.ensureDirectory(logsDir)
            LogsFileUtils.enforceRetention(logsDir, LOG_RETENTION_MAX_FILES, LOG_RETENTION_MAX_BYTES)
            val files = if (logsDir.exists()) logsDir.listFiles()?.filter { it.isFile } ?: emptyList() else emptyList()
            val summaries = files.sortedByDescending { it.lastModified() }
                .map { file ->
                    LogSummary(
                        name = file.name,
                        sizeBytes = file.length(),
                        lastModifiedMillis = file.lastModified(),
                        isCompressed = file.extension.equals("gz", ignoreCase = true)
                    )
                }
            _logs.value = summaries
            announceList(summaries)
        }
    }

    fun delete(summary: LogSummary) {
        scope.launch {
            val target = File(logsDir, summary.name)
            if (target.exists()) {
                target.delete()
            }
            refresh()
        }
    }

    fun readTail(summary: LogSummary, maxLines: Int): List<String> {
        val file = File(logsDir, summary.name)
        return LogsFileUtils.readTail(file, maxLines)
    }

    fun sendToPhone(summary: LogSummary) {
        scope.launch {
            val result = putFile(summary)
            if (!result) {
                sendFailure(summary.name, LOG_MESSAGE_REASON_TOO_LARGE)
            }
        }
    }

    suspend fun handleRequest(fileName: String, sourceNodeId: String) {
        val file = File(logsDir, fileName)
        if (!file.exists()) {
            sendFailure(fileName, LOG_MESSAGE_REASON_MISSING)
            return
        }
        if (file.length() > LOG_TRANSFER_SIZE_LIMIT_BYTES) {
            sendFailure(fileName, LOG_MESSAGE_REASON_TOO_LARGE)
            return
        }
        putFile(LogSummary(file.name, file.length(), file.lastModified(), file.extension.equals("gz", true)))
    }

    private suspend fun putFile(summary: LogSummary): Boolean {
        val file = File(logsDir, summary.name)
        if (!file.exists()) return false
        if (file.length() > LOG_TRANSFER_SIZE_LIMIT_BYTES) {
            return false
        }
        val encodedName = URLEncoder.encode(summary.name, StandardCharsets.UTF_8.name())
        val request = PutDataRequest.createWithAutoAppendedId("$LOG_DATA_ITEM_PREFIX$encodedName")
        request.data = file.readBytes()
        request.setUrgent()
        return try {
            dataClient.putDataItem(request).await()
            true
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun announceList(summaries: List<LogSummary>) {
        val nodes = nodeClient.connectedNodes.await()
        if (nodes.isEmpty()) return
        val payload = JSONArray().apply {
            summaries.forEach { summary ->
                put(
                    JSONObject().apply {
                        put("name", summary.name)
                        put("size", summary.sizeBytes)
                        put("mtime", summary.lastModifiedMillis)
                        put("gz", summary.isCompressed)
                    }
                )
            }
        }.toString().toByteArray(StandardCharsets.UTF_8)
        nodes.forEach { node ->
            messageClient.sendMessage(node.id, LOG_MESSAGE_ANNOUNCE_PATH, payload).await()
        }
    }

    private fun sendFailure(fileName: String, reason: String) {
        scope.launch {
            val nodes = nodeClient.connectedNodes.await()
            val uri = UriBuilder(LOG_MESSAGE_TRANSFER_FAILED_PATH)
                .appendQueryParameter(LOG_MESSAGE_QUERY_FILE, fileName)
                .appendQueryParameter(LOG_MESSAGE_QUERY_REASON, reason)
                .buildString()
            nodes.forEach { node ->
                messageClient.sendMessage(node.id, uri, ByteArray(0)).await()
            }
        }
    }

    companion object {
        @Volatile
        private var instance: WatchLogsRepository? = null

        fun getInstance(context: Context): WatchLogsRepository {
            return instance ?: synchronized(this) {
                instance ?: WatchLogsRepository(context.applicationContext).also { instance = it }
            }
        }
    }
}

private class UriBuilder(private val basePath: String) {
    private val params = mutableMapOf<String, String>()

    fun appendQueryParameter(name: String, value: String?): UriBuilder {
        if (value != null) {
            params[name] = value
        }
        return this
    }

    fun buildString(): String {
        if (params.isEmpty()) return basePath
        val query = params.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
        }
        return "$basePath?$query"
    }
}
