package dev.pointtosky.core.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import java.time.ZoneId
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class ZoneRepo(
    private val context: Context,
    private val zoneProvider: () -> ZoneId = ZoneId::systemDefault,
    private val registerReceiver: (Context, BroadcastReceiver, IntentFilter) -> Unit = { ctx, receiver, filter ->
        ContextCompat.registerReceiver(
            ctx,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    },
    private val unregisterReceiver: (Context, BroadcastReceiver) -> Unit = { ctx, receiver ->
        runCatching { ctx.unregisterReceiver(receiver) }
    },
) {

    val zoneFlow: Flow<ZoneId> = callbackFlow {
        trySend(zoneProvider())
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                trySend(zoneProvider())
            }
        }
        registerReceiver(context, receiver, filter)
        awaitClose {
            unregisterReceiver(context, receiver)
        }
    }

    fun current(): ZoneId = zoneProvider()
}
