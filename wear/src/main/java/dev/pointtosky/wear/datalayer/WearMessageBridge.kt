package dev.pointtosky.wear.datalayer

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Мини-обёртка над Data Layer MessageClient для часов.
 */
object WearMessageBridge {
    /**
     * Отправить payload на ближайший узел (телефон) по пути [path].
     * Без ретраев/очередей — достаточно для S7.E.
     */
    suspend fun sendToPhone(context: Context, path: String, payload: ByteArray) =
        withContext(Dispatchers.IO) {
            val nodeClient = Wearable.getNodeClient(context)
            val nodes = Tasks.await(nodeClient.connectedNodes)
            val node = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull() ?: return@withContext
            val messageClient = Wearable.getMessageClient(context)
            Tasks.await(messageClient.sendMessage(node.id, path, payload))
        }
}
