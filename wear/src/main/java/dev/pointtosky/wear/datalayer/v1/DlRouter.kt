package dev.pointtosky.wear.datalayer.v1

import android.content.Context

object DlRouter {
    fun route(context: Context, path: String, payload: ByteArray) {
        val intent = DlIntents.build(context, path, payload)
        context.sendBroadcast(intent) // динамические ресиверы в UI поймают
    }
}
