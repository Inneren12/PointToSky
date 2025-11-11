package dev.pointtosky.wear.datalayer.v1

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.pointtosky.core.logging.LogBus

/**
 * Приёмник всех сообщений Data Layer (watch).
 *  • валидирует envelope и шлёт /ack;
 *  • транслирует полезную нагрузку дальше (пока просто логируем).
 */
class DlReceiverService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        val data = event.data // non-null; пустой массив проверим отдельно
        if (data.isEmpty()) {
            LogBus.event("dl_recv_empty", mapOf("path" to path))
            return
        }
        if (path == DlPaths.ACK) {
            val (refCid, ok) = DlJson.parseAck(data)
            if (!refCid.isNullOrBlank()) {
                LogBus.event("dl_ack", mapOf("refCid" to refCid, "ok" to (ok ?: true)))
                DlAcks.complete(refCid)
            }
            return
        }
        val cid = DlJson.parseCid(data)
        if (!cid.isNullOrBlank()) {
            // Отбиваем ACK отправителю
            val ack = DlJson.buildAck(cid, ok = true)
            Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, DlPaths.ACK, ack)
        }
        LogBus.event("dl_recv", mapOf("path" to path, "cid" to cid.orEmpty()))
        // route → внутренняя доставка в UI/VM
        DlRouter.route(applicationContext, path, data)
    }
}
