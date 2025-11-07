package dev.pointtosky.mobile.datalayer

import android.content.Intent
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import dev.pointtosky.mobile.tile.tonight.TonightMirrorStore

class TonightWearListenerService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        val payload = String(event.data ?: ByteArray(0), Charsets.UTF_8)
        when (event.path) {
            "/tile/tonight/push_model" -> {
                TonightMirrorStore.applyJson(payload)
            }
            "/tile/tonight/open" -> {
                // Минимальный DoD — открыть Activity и распечатать payload
                val i = Intent(this, dev.pointtosky.mobile.tile.tonight.TonightOpenActivity::class.java).apply {
                    putExtra("payload", payload)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(i)
            }
        }
    }
}
