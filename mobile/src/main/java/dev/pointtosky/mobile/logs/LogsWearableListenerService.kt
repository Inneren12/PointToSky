package dev.pointtosky.mobile.logs

import android.net.Uri
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.pointtosky.mobile.R
import dev.pointtosky.core.common.logs.LOG_DATA_ITEM_PREFIX
import dev.pointtosky.core.common.logs.LOG_MESSAGE_ANNOUNCE_PATH
import dev.pointtosky.core.common.logs.LOG_MESSAGE_QUERY_FILE
import dev.pointtosky.core.common.logs.LOG_MESSAGE_QUERY_REASON
import dev.pointtosky.core.common.logs.LOG_MESSAGE_TRANSFER_FAILED_PATH
import dev.pointtosky.core.common.logs.LOG_MESSAGE_REASON_MISSING
import dev.pointtosky.core.common.logs.LOG_MESSAGE_REASON_TOO_LARGE
import dev.pointtosky.core.common.logs.LogSummary
import dev.pointtosky.core.common.logs.WatchTransferEvent
import org.json.JSONArray
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class LogsWearableListenerService : WearableListenerService() {
    private val repository by lazy { PhoneLogsRepository.getInstance(applicationContext) }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        when {
            messageEvent.path == LOG_MESSAGE_ANNOUNCE_PATH -> handleAnnounceList(messageEvent)
            messageEvent.path.startsWith(LOG_MESSAGE_TRANSFER_FAILED_PATH) -> handleTransferFailure(messageEvent)
        }
    }

    private fun handleAnnounceList(messageEvent: MessageEvent) {
        val payload = messageEvent.data?.toString(StandardCharsets.UTF_8) ?: return
        val jsonArray = JSONArray(payload)
        val summaries = mutableListOf<LogSummary>()
        for (i in 0 until jsonArray.length()) {
            val obj = jsonArray.getJSONObject(i)
            summaries.add(
                LogSummary(
                    name = obj.getString("name"),
                    sizeBytes = obj.getLong("size"),
                    lastModifiedMillis = obj.getLong("mtime"),
                    isCompressed = obj.optBoolean("gz", false)
                )
            )
        }
        WatchAnnouncementsRepository.update(summaries)
    }

    private fun handleTransferFailure(messageEvent: MessageEvent) {
        val uri = Uri.parse(messageEvent.path)
        val fileName = uri.getQueryParameter(LOG_MESSAGE_QUERY_FILE) ?: return
        val reason = uri.getQueryParameter(LOG_MESSAGE_QUERY_REASON)
        val message = when (reason) {
            LOG_MESSAGE_REASON_TOO_LARGE -> getString(R.string.logs_error_too_large)
            LOG_MESSAGE_REASON_MISSING -> getString(R.string.logs_error_missing)
            else -> getString(R.string.logs_error_generic)
        }
        WatchTransferStatusBus.emit(WatchTransferEvent.Completed(fileName, success = false, message = message))
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.use {
            for (event in it) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val item = event.dataItem
                val path = item.uri.path ?: continue
                if (path.startsWith(LOG_DATA_ITEM_PREFIX)) {
                    val fileName = decodeFileName(path.removePrefix(LOG_DATA_ITEM_PREFIX))
                    val data = item.data ?: continue
                    runCatching {
                        repository.saveWatchFile(fileName, data)
                    }.onSuccess {
                        WatchTransferStatusBus.emit(WatchTransferEvent.Completed(fileName, success = true, message = null))
                    }.onFailure { error ->
                        WatchTransferStatusBus.emit(
                            WatchTransferEvent.Completed(
                                fileName = fileName,
                                success = false,
                                message = error.message ?: getString(R.string.logs_error_generic)
                            )
                        )
                    }
                }
            }
        }
    }

    private fun decodeFileName(encoded: String): String {
        return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    }
}
