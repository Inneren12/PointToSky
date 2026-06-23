package dev.pointtosky.wear.datalayer

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

object WearMessageBridge {
    /**
     * Единая точка отправки на телефон.
     * Временная упрощённая версия (без ACK), чтобы исключить зависимость от удалённых классов.
     * Позже можно снова переключить на надёжный мост v1 (ACK + fallback).
     */
    suspend fun sendToPhone(
        context: Context,
        path: String,
        payload: ByteArray,
    ) {
        runCatching {
            val nodeClient = Wearable.getNodeClient(context)
            val nodes = nodeClient.connectedNodes.await()
            val msg = Wearable.getMessageClient(context)
            nodes.forEach { node ->
                runCatching { msg.sendMessage(node.id, path, payload).await() }
            }
        }
    }
}
