package dev.pointtosky.mobile.datalayer

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.pointtosky.core.datalayer.DATA_LAYER_PROTOCOL_VERSION
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.datalayer.PATH_TILE_TONIGHT_PUSH_MODEL
import dev.pointtosky.core.datalayer.TileTonightPushModelMessage
import dev.pointtosky.mobile.settings.MobileSettings
import dev.pointtosky.mobile.tile.tonight.TonightMirrorStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Минималистичный приёмник Data Layer: обрабатываем push модели тайла.
 * (Без общего ReliableDataLayerBridge — упрощённый путь до завершения S8A.)
 */
class MobileBridgeListenerService : WearableListenerService() {
    private val settings: MobileSettings by lazy { MobileSettings.from(applicationContext) }

    override fun onMessageReceived(event: MessageEvent) {
        when (event.path) {
            PATH_TILE_TONIGHT_PUSH_MODEL -> {
                runCatching {
                    val currentSettings = runBlocking { settings.state.first() }
                    if (!currentSettings.mirrorEnabled) return
                    val msg = JsonCodec.decode<TileTonightPushModelMessage>(event.data)
                    if (msg.v == DATA_LAYER_PROTOCOL_VERSION) {
                        TonightMirrorStore.applyJson(
                            msg.payload.toString(),
                            redacted = currentSettings.redactPayloads,
                        )
                    }
                }
            }
            // TODO: /ack и остальные пути можно добавить позже
        }
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        // Fallback DataClient пока не используется для этой ветки.
        super.onDataChanged(dataEvents)
    }
}
