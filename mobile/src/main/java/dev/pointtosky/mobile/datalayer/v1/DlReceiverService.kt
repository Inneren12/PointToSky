package dev.pointtosky.mobile.datalayer.v1

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.pointtosky.core.datalayer.CardOpenMessage
import dev.pointtosky.core.datalayer.DATA_LAYER_PROTOCOL_VERSION
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.datalayer.PATH_ACK
import dev.pointtosky.core.datalayer.PATH_CARD_OPEN
import dev.pointtosky.core.datalayer.PATH_TILE_TONIGHT_PUSH_MODEL
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.mobile.card.CardDeepLinkLauncher
import dev.pointtosky.mobile.card.CardObjectPayload
import dev.pointtosky.mobile.card.CardRepository
import dev.pointtosky.mobile.card.toEntry
import dev.pointtosky.mobile.logging.MobileLog
import dev.pointtosky.mobile.settings.MobileSettings
import dev.pointtosky.mobile.settings.from
import dev.pointtosky.mobile.tile.tonight.TonightMirrorStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Приём на телефоне: шлём ACK обратно на часы и логируем полезную нагрузку.
 * Далее можно пробрасывать в UI (карточки, превью тайла, Aim и т.д.).
 */
class DlReceiverService : WearableListenerService() {
    private val settings: MobileSettings by lazy { MobileSettings.from(applicationContext) }

    companion object {
        // App-scoped: applying the mirror must survive the short-lived listener service's teardown.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onMessageReceived(event: MessageEvent) {
        val path = event.path
        val data = event.data
        when (path) {
            PATH_ACK -> {
                // Телефон тоже может ждать ACK для своих исходящих сообщений — на будущее.
                val (refCid, ok) = DlJson.parseAck(data)
                MobileLog.bridgeRecv(path = path, cid = refCid, nodeId = event.sourceNodeId)
                if (!refCid.isNullOrBlank()) {
                    LogBus.event("dl_ack", mapOf("refCid" to refCid, "ok" to (ok ?: true)))
                }
            }
            else -> {
                val cid = DlJson.parseCid(data)
                MobileLog.bridgeRecv(path = path, cid = cid, nodeId = event.sourceNodeId)
                if (!cid.isNullOrBlank()) {
                    val ack = DlJson.buildAck(cid, ok = true)
                    MobileLog.bridgeSend(
                        path = PATH_ACK,
                        cid = cid,
                        nodeId = event.sourceNodeId,
                        attempt = 1,
                        payloadBytes = ack.size,
                    )
                    DlMessageSender.sendMessage(
                        context = this,
                        nodeId = event.sourceNodeId,
                        path = PATH_ACK,
                        payload = ack,
                    ) { error ->
                        MobileLog.bridgeError(
                            path = PATH_ACK,
                            cid = cid,
                            nodeId = event.sourceNodeId,
                            error = error.message,
                        )
                    }
                }
                LogBus.event("dl_recv", mapOf("path" to path))
                if (path == PATH_CARD_OPEN) {
                    handleCardOpen(data, cid, event.sourceNodeId)
                }
                if (path == PATH_TILE_TONIGHT_PUSH_MODEL) {
                    handleTilePush(data, event.sourceNodeId)
                }
            }
        }
    }

    private fun handleTilePush(data: ByteArray, nodeId: String) {
        scope.launch {
            runCatching {
                val s = settings.state.first()
                if (!s.mirrorEnabled) return@launch
                TonightMirrorStore.applyJson(String(data, Charsets.UTF_8), redacted = s.redactPayloads)
            }.onFailure { error ->
                MobileLog.bridgeError(
                    path = PATH_TILE_TONIGHT_PUSH_MODEL,
                    cid = null,
                    nodeId = nodeId,
                    error = error.message ?: error::class.java.simpleName,
                )
            }
        }
    }

    private fun handleCardOpen(
        data: ByteArray,
        fallbackCid: String?,
        nodeId: String,
    ) {
        runCatching {
            val message = JsonCodec.decode<CardOpenMessage>(data)
            if (message.v != DATA_LAYER_PROTOCOL_VERSION) return@runCatching
            val payload = JsonCodec.decodeFromElement<CardObjectPayload>(message.obj)
            val entry = payload.toEntry(message.cid.ifBlank { fallbackCid })
            val resolvedId =
                when (entry) {
                    is CardRepository.Entry.Ready -> entry.model.id
                    is CardRepository.Entry.Invalid ->
                        payload.id
                            ?: message.cid.takeIf { it.isNotBlank() }
                            ?: fallbackCid
                }
            if (resolvedId != null) {
                CardRepository.update(resolvedId, entry)
                val type = (entry as? CardRepository.Entry.Ready)?.model?.type?.name
                MobileLog.cardOpen(source = "watch", id = resolvedId, type = type)
                CardDeepLinkLauncher.launch(applicationContext, resolvedId)
            } else {
                LogBus.event("dl_error", mapOf("err" to "card_no_id"))
            }
        }.onFailure { error ->
            LogBus.event("dl_error", mapOf("err" to (error.message ?: error::class.java.simpleName)))
            MobileLog.bridgeError(
                path = PATH_CARD_OPEN,
                cid = fallbackCid,
                nodeId = nodeId,
                error = error.message ?: error::class.java.simpleName,
            )
        }
    }
}
