package dev.pointtosky.wear.datalayer

import com.google.android.gms.wearable.MessageEvent
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

class WearBridgeListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val data: ByteArray = event.data // уже non-nullable
        val path = event.path

        fun logError(path: String, error: Throwable) {
            LogBus.event(
                "dl_error",
                mapOf(
                    "err" to (error.message ?: error::class.java.simpleName),
                    "path" to path,
                ),
            )
        }

        fun handle(path: String, block: () -> Unit) {
            runCatching(block).onFailure { logError(path, it) }
        }

        when (path) {
            PATH_AIM_SET_TARGET -> handle(PATH_AIM_SET_TARGET) {
                val msg = JsonCodec.decode<AimSetTargetMessage>(data)
                if (msg.v == DATA_LAYER_PROTOCOL_VERSION) {
                    WearBridge.handleAimSetTargetMessage(applicationContext, msg)
                }
            }

            PATH_APP_OPEN -> handle(PATH_APP_OPEN) {
                val msg = JsonCodec.decode<AppOpenMessage>(data)
                if (msg.v == DATA_LAYER_PROTOCOL_VERSION) {
                    WearBridge.handleAppOpenMessage(applicationContext, msg)
                }
            }

            PATH_SENSOR_HEADING -> handle(PATH_SENSOR_HEADING) {
                val msg = JsonCodec.decode<SensorHeadingMessage>(data)
                if (msg.v == DATA_LAYER_PROTOCOL_VERSION) {
                    PhoneHeadingBridge.updateHeading(msg.azDeg, msg.ts)
                }
            }

            else -> {
                LogBus.event("dl_error", mapOf("err" to "unknown_path", "path" to path))
            }
        }
    }
}
