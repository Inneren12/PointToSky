package dev.pointtosky.mobile.datalayer

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import dev.pointtosky.mobile.logging.MobileLog
import java.util.UUID

/**
 * Простой отправитель сообщений на часы через MessageClient (без ACK/Retry).
 * Сохраняем внешний API: MobileBridge.get(context).send(path) { cid -> bytes }.
 */
object MobileBridge {
    @Volatile
    private var instance: Sender? = null

    @Volatile
    private var transportOverride: Sender.Transport? = null

    fun get(context: Context): Sender {
        val cached = instance
        if (cached != null) return cached
        return synchronized(this) {
            instance ?: Sender(context.applicationContext).also { instance = it }
        }
    }

    @androidx.annotation.VisibleForTesting
    fun overrideTransportForTests(transport: Sender.Transport?) {
        transportOverride = transport
    }

    @androidx.annotation.VisibleForTesting
    fun resetForTests() {
        instance = null
    }

    class Sender(
        private val context: Context,
    ) {
        data class Ack(
            val ok: Boolean,
            val err: String? = null,
        )

        interface Transport {
            data class Node(
                val id: String,
            )

            @Throws(Exception::class)
            fun connectedNodes(): List<Node>

            @Throws(Exception::class)
            fun sendMessage(
                nodeId: String,
                path: String,
                payload: ByteArray,
            )
        }

        private val wearTransport by lazy { WearTransport(context) }

        private fun transport(): Transport = transportOverride ?: wearTransport

        /**
         * Отправляем всем подключённым нодам. Возвращаем примитивный Ack (без настоящего подтверждения).
         */
        fun send(
            path: String,
            build: (cid: String) -> ByteArray,
        ): Ack? {
            val cid = UUID.randomUUID().toString()
            val payload =
                runCatching { build(cid) }.getOrElse { t ->
                    MobileLog.bridgeError(path = path, cid = cid, nodeId = null, error = t.message)
                    return Ack(ok = false, err = t.message)
                }
            val nodes =
                runCatching { transport().connectedNodes() }.getOrElse { error ->
                    MobileLog.bridgeError(path = path, cid = cid, nodeId = null, error = error.message)
                    return Ack(ok = false, err = error.message)
                }
            if (nodes.isEmpty()) {
                MobileLog.bridgeError(path = path, cid = cid, nodeId = null, error = "no_nodes")
                return Ack(ok = false, err = "no_nodes")
            }
            nodes.forEach { node ->
                var attempt = 1
                while (attempt <= MAX_SEND_ATTEMPTS) {
                    if (attempt == 1) {
                        MobileLog.bridgeSend(path, cid, node.id, attempt, payload.size)
                    } else {
                        MobileLog.bridgeRetry(path, cid, node.id, attempt, payload.size)
                    }
                    val result =
                        runCatching {
                            transport().sendMessage(node.id, path, payload)
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

        private class WearTransport(
            private val context: Context,
        ) : Transport {
            override fun connectedNodes(): List<Transport.Node> {
                val nodes = Tasks.await(Wearable.getNodeClient(context).connectedNodes)
                return nodes.map { node -> Transport.Node(node.id) }
            }

            override fun sendMessage(
                nodeId: String,
                path: String,
                payload: ByteArray,
            ) {
                Tasks.await(Wearable.getMessageClient(context).sendMessage(nodeId, path, payload))
            }
        }
    }
}
