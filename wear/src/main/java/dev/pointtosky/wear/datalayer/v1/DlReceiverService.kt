package dev.pointtosky.wear.datalayer.v1

import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import com.google.android.gms.wearable.Wearable
import dev.pointtosky.core.logging.LogBus
import java.nio.charset.StandardCharsets

/**
 * Приёмник всех сообщений Data Layer (watch).
 *  • валидирует envelope и шлёт /ack;
 *  • транслирует полезную нагрузку дальше (пока просто логируем).
 */
class DlReceiverService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        val data = event.data ?: ByteArray(0)
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
        LogBus.event("dl_recv", mapOf("path" to path))
        // TODO(route): здесь можно пробрасывать intent/broadcast в нужные экраны/VM.
    }
}
