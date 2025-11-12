package dev.pointtosky.wear.datalayer.v1

import android.content.Context
import android.content.Intent
import android.content.IntentFilter

object DlIntents {
    const val ACTION_MESSAGE = "dev.pointtosky.dl.MESSAGE"
    const val EXTRA_PATH = "extra_path"
    const val EXTRA_PAYLOAD = "extra_payload"

    fun filter(): IntentFilter = IntentFilter(ACTION_MESSAGE)

    fun build(
        context: Context,
        path: String,
        payload: ByteArray,
    ): Intent =
        Intent(ACTION_MESSAGE).apply {
            setPackage(context.packageName) // только своё приложение
            putExtra(EXTRA_PATH, path)
            putExtra(EXTRA_PAYLOAD, payload)
        }
}
