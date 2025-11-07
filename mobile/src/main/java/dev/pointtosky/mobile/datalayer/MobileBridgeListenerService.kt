package dev.pointtosky.mobile.datalayer

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.pointtosky.core.datalayer.ReliableDataLayerBridge

class MobileBridgeListenerService : WearableListenerService() {
    private val bridge: ReliableDataLayerBridge by lazy { MobileBridge.get(this) }

    override fun onMessageReceived(event: MessageEvent) {
        bridge.onMessageReceived(event)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        bridge.onDataChanged(dataEvents)
    }
}
