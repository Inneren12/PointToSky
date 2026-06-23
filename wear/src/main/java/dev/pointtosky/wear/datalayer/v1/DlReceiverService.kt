package dev.pointtosky.wear.datalayer.v1

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.pointtosky.core.datalayer.AimSetTargetMessage
import dev.pointtosky.core.datalayer.AppOpenMessage
import dev.pointtosky.core.datalayer.DATA_LAYER_PROTOCOL_VERSION
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.datalayer.PATH_AIM_SET_TARGET
import dev.pointtosky.core.datalayer.PATH_APP_OPEN
import dev.pointtosky.core.datalayer.PATH_SENSOR_HEADING
import dev.pointtosky.core.datalayer.SensorHeadingMessage
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.wear.datalayer.PhoneHeadingBridge
import dev.pointtosky.wear.datalayer.WearBridge

/**
 * Приёмник всех сообщений Data Layer (watch).
 *  • валидирует envelope и шлёт /ack;
 *  • диспатчит напрямую — работает из фона (Play Services поднимает процесс).
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
        if (!cid.isNullOrBlank() && path != PATH_SENSOR_HEADING) {
            // Отбиваем ACK отправителю
            val ack = DlJson.buildAck(cid, ok = true)
            Wearable.getMessageClient(this).sendMessage(event.sourceNodeId, DlPaths.ACK, ack)
        }
        LogBus.event("dl_recv", mapOf("path" to path, "cid" to cid.orEmpty()))
        // Прямой диспатч по пути — работает и из фона: сервис поднимается Play Services,
        // а WearBridge.handle* запускает активити через PendingIntent (startActivitySafe).
        // Broadcast-путь ловился только в onStart..onStop, поэтому фон-команды терялись.
        when (path) {
            PATH_AIM_SET_TARGET -> dispatch(path) {
                val msg = JsonCodec.decode<AimSetTargetMessage>(data)
                if (msg.v == DATA_LAYER_PROTOCOL_VERSION) {
                    WearBridge.handleAimSetTargetMessage(applicationContext, msg)
                }
            }
            PATH_APP_OPEN -> dispatch(path) {
                val msg = JsonCodec.decode<AppOpenMessage>(data)
                if (msg.v == DATA_LAYER_PROTOCOL_VERSION) {
                    WearBridge.handleAppOpenMessage(applicationContext, msg)
                }
            }
            PATH_SENSOR_HEADING -> dispatch(path) {
                val msg = JsonCodec.decode<SensorHeadingMessage>(data)
                if (msg.v == DATA_LAYER_PROTOCOL_VERSION) {
                    PhoneHeadingBridge.updateHeading(msg.azDeg, msg.ts)
                }
            }
            else -> Unit
        }
    }

    private fun dispatch(path: String, block: () -> Unit) {
        runCatching(block).onFailure { e ->
            LogBus.event("dl_error", mapOf("err" to (e.message ?: e::class.java.simpleName), "path" to path))
        }
    }
}
