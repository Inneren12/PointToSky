package dev.pointtosky.mobile.datalayer

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.UUID
import dev.pointtosky.mobile.logging.MobileLog

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
                MobileLog.bridgeError(path = path, cid = cid, nodeId = null, error = t.message)
                return Ack(ok = false, err = t.message)
            }
            val nodes = runCatching { Tasks.await(Wearable.getNodeClient(context).connectedNodes) }
                .getOrElse { error ->
                    MobileLog.bridgeError(path = path, cid = cid, nodeId = null, error = error.message)
                    return Ack(ok = false, err = error.message)
                }
            if (nodes.isEmpty()) {
                MobileLog.bridgeError(path = path, cid = cid, nodeId = null, error = "no_nodes")
                return Ack(ok = false, err = "no_nodes")
            }
            val messageClient = Wearable.getMessageClient(context)
            nodes.forEach { node ->
                var attempt = 1
                while (attempt <= MAX_SEND_ATTEMPTS) {
                    if (attempt == 1) {
                        MobileLog.bridgeSend(path, cid, node.id, attempt, payload.size)
                    } else {
                        MobileLog.bridgeRetry(path, cid, node.id, attempt, payload.size)
                    }
                    val result = runCatching {
                        Tasks.await(messageClient.sendMessage(node.id, path, payload))
                    }
                    if (result.isSuccess) {
                        break
                    }
                    if (attempt == MAX_SEND_ATTEMPTS) {
                        val error = result.exceptionOrNull()
                        MobileLog.bridgeError(path, cid, node.id, error?.message)
                        return Ack(ok = false, err = error?.message)
                    }
                    attempt++
                }
            }
            return Ack(ok = true)
        }

        private companion object {
            private const val MAX_SEND_ATTEMPTS = 2
        }
    }
}
