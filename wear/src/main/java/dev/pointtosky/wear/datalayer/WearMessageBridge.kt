package dev.pointtosky.wear.datalayer

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable

object WearMessageBridge {
    /**
     * Единая точка отправки на телефон.
     * Временная упрощённая версия (без ACK), чтобы исключить зависимость от удалённых классов.
     * Позже можно снова переключить на надёжный мост v1 (ACK + fallback).
     */
    fun sendToPhone(
        context: Context,
        path: String,
        payload: ByteArray,
    ) {
        runCatching {
            val nodeClient = Wearable.getNodeClient(context)
            val nodes = Tasks.await(nodeClient.connectedNodes)
            val msg = Wearable.getMessageClient(context)
            nodes.forEach { node ->
                runCatching { Tasks.await(msg.sendMessage(node.id, path, payload)) }
            }
        }
    }
}
