package dev.pointtosky.wear.datalayer

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.pointtosky.core.datalayer.ReliableDataLayerBridge

class WearBridgeListenerService : WearableListenerService() {
    private val bridge: ReliableDataLayerBridge by lazy { WearBridge.get(this) }

    override fun onMessageReceived(event: MessageEvent) {
        bridge.onMessageReceived(event)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        bridge.onDataChanged(dataEvents)
    }
}
