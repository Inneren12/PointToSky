package dev.pointtosky.mobile.datalayer.v1

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.pointtosky.core.datalayer.CardOpenMessage
import dev.pointtosky.core.datalayer.DATA_LAYER_PROTOCOL_VERSION
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.datalayer.PATH_CARD_OPEN
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.mobile.card.CardDeepLinkLauncher
import dev.pointtosky.mobile.card.CardObjectPayload
import dev.pointtosky.mobile.card.CardRepository
import dev.pointtosky.mobile.card.toEntry

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
        when (path) {
            PATH_CARD_OPEN -> handleCardOpen(data, cid)
        }
    }

    private fun handleCardOpen(data: ByteArray, fallbackCid: String?) {
        runCatching {
            val message = JsonCodec.decode<CardOpenMessage>(data)
            if (message.v != DATA_LAYER_PROTOCOL_VERSION) return
            val payload = JsonCodec.decodeFromElement<CardObjectPayload>(message.obj)
            val entry = payload.toEntry(message.cid.ifBlank { fallbackCid })
            val resolvedId = when (entry) {
                is CardRepository.Entry.Ready -> entry.model.id
                is CardRepository.Entry.Invalid -> payload.id
                    ?: message.cid.takeIf { it.isNotBlank() }
                    ?: fallbackCid
            }
            if (resolvedId != null) {
                CardRepository.update(resolvedId, entry)
                CardDeepLinkLauncher.launch(applicationContext, resolvedId)
            } else {
                LogBus.event("dl_error", mapOf("err" to "card_no_id"))
            }
        }.onFailure { error ->
            LogBus.event("dl_error", mapOf("err" to (error.message ?: error::class.java.simpleName)))
        }
    }
}
