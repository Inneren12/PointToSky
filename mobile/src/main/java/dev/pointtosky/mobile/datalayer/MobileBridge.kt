package dev.pointtosky.mobile.datalayer

import android.content.Context
import dev.pointtosky.core.datalayer.DATA_LAYER_PROTOCOL_VERSION
import dev.pointtosky.core.datalayer.JsonCodec
import dev.pointtosky.core.datalayer.PATH_TILE_TONIGHT_PUSH_MODEL
import dev.pointtosky.core.datalayer.ReliableDataLayerBridge
import dev.pointtosky.core.datalayer.TileTonightPushModelMessage
import dev.pointtosky.core.logging.LogBus
import dev.pointtosky.mobile.tile.tonight.TonightMirrorStore

object MobileBridge {
    @Volatile
    private var instance: ReliableDataLayerBridge? = null

    fun get(context: Context): ReliableDataLayerBridge {
        val existing = instance
        if (existing != null) return existing
        return synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }
    }

    private fun create(context: Context): ReliableDataLayerBridge {
        val bridge = ReliableDataLayerBridge(
            context = context,
            logger = { name, payload -> LogBus.event(name, payload) },
        )
        bridge.registerHandler(PATH_TILE_TONIGHT_PUSH_MODEL) { envelope ->
            val message = runCatching {
                JsonCodec.decode<TileTonightPushModelMessage>(envelope.bytes)
            }.getOrNull() ?: return@registerHandler false
            if (message.v != DATA_LAYER_PROTOCOL_VERSION) return@registerHandler false
            val payloadJson = message.payload.toString()
            runCatching { TonightMirrorStore.applyJson(payloadJson) }.isSuccess
        }
        return bridge
    }
}
