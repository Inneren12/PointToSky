package dev.pointtosky.mobile.datalayer.v1

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.pointtosky.core.logging.LogBus

/**
 * Приём на телефоне: шлём ACK обратно на часы и логируем полезную нагрузку.
 * Далее можно пробрасывать в UI (карточки, превью тайла, Aim и т.д.).
 */
class DlReceiverService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        val data = event.data ?: ByteArray(0)
        if (path == DlPaths.ACK) {
            // Телефон тоже может ждать ACK для своих исходящих сообщений — на будущее.
            val (refCid, ok) = DlJson.parseAck(data)
            if (!refCid.isNullOrBlank()) {
                LogBus.event("dl_ack", mapOf("refCid" to refCid, "ok" to (ok ?: true)))
            }
            return
        }
        val cid = DlJson.parseCid(data)
        if (!cid.isNullOrBlank()) {
            val ack = DlJson.buildAck(cid, ok = true)
            Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, DlPaths.ACK, ack)
        }
        LogBus.event("dl_recv", mapOf("path" to path))
        // TODO(route): распарсить payload и открыть нужный экран.
    }
}
