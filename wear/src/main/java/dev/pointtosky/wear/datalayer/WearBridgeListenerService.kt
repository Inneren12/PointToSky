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
        val data = event.data ?: ByteArray(0)
        when (event.path) {
            PATH_AIM_SET_TARGET -> {
                runCatching {
                    val message = JsonCodec.decode<AimSetTargetMessage>(data)
                    if (message.v == DATA_LAYER_PROTOCOL_VERSION) {
                        WearBridge.handleAimSetTargetMessage(applicationContext, message)
                    }
                }.onFailure { error ->
                    LogBus.event(
                        "dl_error",
                        mapOf(
                            "err" to (error.message ?: error::class.java.simpleName),
                            "path" to PATH_AIM_SET_TARGET,
                        ),
                    )
                }
            }

            PATH_APP_OPEN -> {
                runCatching {
                    val message = JsonCodec.decode<AppOpenMessage>(data)
                    if (message.v == DATA_LAYER_PROTOCOL_VERSION) {
                        WearBridge.handleAppOpenMessage(applicationContext, message)
                    }
                }.onFailure { error ->
                    LogBus.event(
                        "dl_error",
                        mapOf(
                            "err" to (error.message ?: error::class.java.simpleName),
                            "path" to PATH_APP_OPEN,
                        ),
                    )
                }
            }

            PATH_SENSOR_HEADING -> {
                runCatching {
                    val message = JsonCodec.decode<SensorHeadingMessage>(data)
                    if (message.v == DATA_LAYER_PROTOCOL_VERSION) {
                        PhoneHeadingBridge.updateHeading(message.azDeg, message.ts)
                    }
                }.onFailure { error ->
                    LogBus.event(
                        "dl_error",
                        mapOf(
                            "err" to (error.message ?: error::class.java.simpleName),
                            "path" to PATH_SENSOR_HEADING,
                        ),
                    )
                }
            }
        }
    }
}
