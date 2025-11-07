package dev.pointtosky.mobile.datalayer

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.UUID

/**
 * Простой отправитель сообщений на часы через MessageClient (без ACK/Retry).
 * Сохраняем внешний API: MobileBridge.get(context).send(path) { cid -> bytes }.
 */
object MobileBridge {
    @Volatile
    private var instance: Sender? = null

    fun get(context: Context): Sender {
        val cached = instance
        if (cached != null) return cached
        return synchronized(this) {
            instance ?: Sender(context.applicationContext).also { instance = it }
        }
    }

    class Sender(private val context: Context) {
        data class Ack(val ok: Boolean, val err: String? = null)

        /**
         * Отправляем всем подключённым нодам. Возвращаем примитивный Ack (без настоящего подтверждения).
         */
        fun send(path: String, build: (cid: String) -> ByteArray): Ack? {
            val cid = UUID.randomUUID().toString()
            val payload = runCatching { build(cid) }.getOrElse { t ->
                return Ack(ok = false, err = t.message)
            }
            return runCatching {
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                val msg = Wearable.getMessageClient(context)
                nodes.forEach { node ->
                    Tasks.await(msg.sendMessage(node.id, path, payload))
                }
                Ack(ok = true)
            }.getOrElse { t -> Ack(ok = false, err = t.message) }
        }
    }
}
