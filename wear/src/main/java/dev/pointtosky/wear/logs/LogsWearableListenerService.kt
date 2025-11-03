package dev.pointtosky.wear.logs

import android.net.Uri
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.pointtosky.core.common.logs.LOG_MESSAGE_QUERY_FILE
import dev.pointtosky.core.common.logs.LOG_MESSAGE_REQUEST_PATH
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LogsWearableListenerService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val repository by lazy { WatchLogsRepository.getInstance(applicationContext) }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path.startsWith(LOG_MESSAGE_REQUEST_PATH)) {
            val fileName = Uri.parse(messageEvent.path).getQueryParameter(LOG_MESSAGE_QUERY_FILE) ?: return
            val nodeId = messageEvent.sourceNodeId
            scope.launch {
                repository.handleRequest(fileName, nodeId)
            }
        }
    }
}
