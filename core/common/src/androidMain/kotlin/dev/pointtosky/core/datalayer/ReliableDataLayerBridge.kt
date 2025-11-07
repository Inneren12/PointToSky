package dev.pointtosky.core.datalayer

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.NodeClient
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Shared reliable bridge between phone and watch.
 */
class ReliableDataLayerBridge(
    context: Context,
    coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.IO,
    private val ackTimeoutMs: Long = DEFAULT_ACK_TIMEOUT_MS,
    private val retryTtlMs: Long = TimeUnit.HOURS.toMillis(24),
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String, Map<String, Any?>) -> Unit = { _, _ -> },
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(coroutineContext)

    private val messageClient: MessageClient = Wearable.getMessageClient(appContext)
    private val dataClient: DataClient = Wearable.getDataClient(appContext)
    private val nodeClient: NodeClient = Wearable.getNodeClient(appContext)

    private val ackWaiters = ConcurrentHashMap<String, CompletableDeferred<AckMessage>>()
    private val handlers = ConcurrentHashMap<String, suspend (Envelope) -> Boolean>()
    private val localNodeMutex = Mutex()
    private var localNodeId: String? = null

    fun registerHandler(path: String, handler: suspend (Envelope) -> Boolean) {
        handlers[path] = handler
    }

    fun unregisterHandler(path: String) {
        handlers.remove(path)
    }

    fun onMessageReceived(event: MessageEvent) {
        val bytes = event.data ?: ByteArray(0)
        if (event.path == PATH_ACK) {
            handleAck(bytes)
            return
        }
        dispatchIncoming(
            path = event.path,
            bytes = bytes,
            sourceNodeId = event.sourceNodeId,
            viaDataItem = false,
            fallbackCid = null,
        )
    }

    fun onDataChanged(buffer: DataEventBuffer) {
        buffer.use {
            for (event in it) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val uri = event.dataItem.uri
                if (!uri.path.orEmpty().startsWith(PENDING_PATH_PREFIX)) continue
                val dataItem = DataMapItem.fromDataItem(event.dataItem)
                val origin = dataItem.dataMap.getString(KEY_ORIGIN)
                scope.launch {
                    val localId = getLocalNodeId()
                    if (origin != null && origin == localId) {
                        // Ignore echoes of our own pending items.
                        return@launch
                    }
                    val expiresAt = dataItem.dataMap.getLong(KEY_EXPIRES_AT)
                    if (expiresAt != 0L && clock() > expiresAt) {
                        deleteDataItem(uri)
                        return@launch
                    }
                    val path = dataItem.dataMap.getString(KEY_PATH) ?: return@launch
                    val payload = dataItem.dataMap.getByteArray(KEY_PAYLOAD) ?: return@launch
                    val cid = dataItem.dataMap.getString(KEY_CID)
                    dispatchIncoming(
                        path = path,
                        bytes = payload,
                        sourceNodeId = uri.host,
                        viaDataItem = true,
                        fallbackCid = cid,
                    )
                }
            }
        }
    }

    suspend fun send(path: String, buildPayload: (cid: String) -> ByteArray): AckMessage? {
        val cid = newCid()
        val payload = buildPayload(cid)
        return sendWithPayload(path, payload, cid)
    }

    suspend fun sendWithPayload(path: String, payload: ByteArray, cid: String): AckMessage? {
        val envelope = Envelope(
            path = path,
            bytes = payload,
            cid = cid,
            timestampMs = clock(),
        )
        return performSend(envelope)
    }

    private fun dispatchIncoming(
        path: String,
        bytes: ByteArray,
        sourceNodeId: String?,
        viaDataItem: Boolean,
        fallbackCid: String?,
    ) {
        val envelope = parseEnvelope(path, bytes, fallbackCid)
        if (envelope == null) {
            val cid = fallbackCid
            if (cid != null && sourceNodeId != null) {
                scope.launch { sendAck(sourceNodeId, cid, ok = false, err = "invalid_payload") }
            }
            logger(
                "dl_recv",
                mapOf(
                    "path" to path,
                    "cid" to (fallbackCid ?: ""),
                    "via" to if (viaDataItem) "data" else "message",
                    "error" to "invalid_payload",
                ),
            )
            return
        }
        logger(
            "dl_recv",
            mapOf(
                "path" to path,
                "cid" to envelope.cid,
                "via" to if (viaDataItem) "data" else "message",
            ),
        )
        val handler = handlers[path]
        if (handler == null) {
            if (sourceNodeId != null) {
                scope.launch { sendAck(sourceNodeId, envelope.cid, ok = false, err = "no_handler") }
            }
            return
        }
        scope.launch {
            val ok = try {
                handler(envelope)
            } catch (t: Throwable) {
                false
            }
            if (sourceNodeId != null) {
                sendAck(sourceNodeId, envelope.cid, ok = ok, err = if (ok) null else "handler_failed")
            }
        }
    }

    private fun parseEnvelope(path: String, bytes: ByteArray, fallbackCid: String?): Envelope? {
        val jsonElement = runCatching {
            Json.parseToJsonElement(bytes.decodeToString())
        }.getOrNull() ?: return null
        val obj = jsonElement.jsonObject
        val version = obj["v"]?.jsonPrimitive?.intOrNull
        if (version != DATA_LAYER_PROTOCOL_VERSION) return null
        val cid = obj["cid"]?.jsonPrimitive?.content ?: fallbackCid ?: return null
        return Envelope(path = path, bytes = bytes, cid = cid, timestampMs = clock())
    }

    private suspend fun performSend(envelope: Envelope): AckMessage? {
        val waiter = CompletableDeferred<AckMessage>()
        ackWaiters[envelope.cid] = waiter
        logger(
            "dl_send",
            mapOf(
                "path" to envelope.path,
                "cid" to envelope.cid,
            ),
        )
        val nodeId = selectNodeId() ?: run {
            scheduleRetry(envelope, reason = "no_nodes")
            ackWaiters.remove(envelope.cid)
            return null
        }
        val sendResult = runCatching {
            withContext(Dispatchers.IO) {
                Tasks.await(messageClient.sendMessage(nodeId, envelope.path, envelope.bytes))
            }
        }
        if (sendResult.isFailure) {
            scheduleRetry(envelope, reason = sendResult.exceptionOrNull()?.javaClass?.simpleName ?: "send_failed")
            ackWaiters.remove(envelope.cid)
            return null
        }
        val ack = withTimeoutOrNull(ackTimeoutMs) { waiter.await() }
        if (ack != null) {
            return ack
        }
        scheduleRetry(envelope, reason = "ack_timeout")
        return null
    }

    private suspend fun sendAck(nodeId: String?, refCid: String, ok: Boolean, err: String?) {
        val ackBytes = JsonCodec.encode(
            AckMessage(
                refCid = refCid,
                ok = ok,
                err = err,
            ),
        )
        logger(
            "dl_ack",
            mapOf(
                "cid" to refCid,
                "dir" to "send",
                "ok" to ok,
            ),
        )
        val targetNodeId = nodeId ?: selectNodeId() ?: return
        runCatching {
            withContext(Dispatchers.IO) {
                Tasks.await(messageClient.sendMessage(targetNodeId, PATH_ACK, ackBytes))
            }
        }
    }

    private fun handleAck(bytes: ByteArray) {
        val ack = runCatching { JsonCodec.decode<AckMessage>(bytes) }.getOrNull() ?: return
        if (ack.v != DATA_LAYER_PROTOCOL_VERSION) return
        logger(
            "dl_ack",
            mapOf(
                "cid" to ack.refCid,
                "dir" to "recv",
                "ok" to ack.ok,
            ),
        )
        ackWaiters.remove(ack.refCid)?.complete(ack)
        scope.launch { deletePending(ack.refCid) }
    }

    private suspend fun scheduleRetry(envelope: Envelope, reason: String) {
        logger(
            "dl_retry",
            mapOf(
                "path" to envelope.path,
                "cid" to envelope.cid,
                "reason" to reason,
            ),
        )
        val origin = runCatching { getLocalNodeId() }.getOrNull()
        val request = PutDataMapRequest.create("$PENDING_PATH_PREFIX/${envelope.cid}").apply {
            dataMap.putString(KEY_PATH, envelope.path)
            dataMap.putByteArray(KEY_PAYLOAD, envelope.bytes)
            dataMap.putString(KEY_CID, envelope.cid)
            dataMap.putLong(KEY_TIMESTAMP, envelope.timestampMs)
            dataMap.putLong(KEY_EXPIRES_AT, envelope.timestampMs + retryTtlMs)
            origin?.let { dataMap.putString(KEY_ORIGIN, it) }
        }.asPutDataRequest().apply {
            setUrgent()
        }
        withContext(Dispatchers.IO) {
            Tasks.await(dataClient.putDataItem(request))
        }
    }

    private suspend fun deletePending(cid: String) {
        val uri = pendingUri(cid)
        withContext(Dispatchers.IO) {
            runCatching { Tasks.await(dataClient.deleteDataItems(uri)) }
        }
    }

    private suspend fun deleteDataItem(uri: Uri) {
        withContext(Dispatchers.IO) {
            runCatching { Tasks.await(dataClient.deleteDataItems(uri)) }
        }
    }

    private suspend fun getLocalNodeId(): String {
        localNodeId?.let { return it }
        return localNodeMutex.withLock {
            localNodeId?.let { return it }
            val node = withContext(Dispatchers.IO) { Tasks.await(nodeClient.localNode) }
            localNodeId = node.id
            node.id
        }
    }

    private suspend fun selectNodeId(): String? {
        val nodes = withContext(Dispatchers.IO) { Tasks.await(nodeClient.connectedNodes) }
        return chooseNode(nodes)?.id
    }

    private fun chooseNode(nodes: List<Node>): Node? {
        return nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
    }

    private fun pendingUri(cid: String): Uri = PutDataMapRequest.create("$PENDING_PATH_PREFIX/$cid").uri

    private fun newCid(): String = UUID.randomUUID().toString()

    private companion object {
        private const val DEFAULT_ACK_TIMEOUT_MS = 2_500L
        private const val PENDING_PATH_PREFIX = "/bridge/pending"
        private const val KEY_PATH = "path"
        private const val KEY_PAYLOAD = "payload"
        private const val KEY_TIMESTAMP = "ts"
        private const val KEY_EXPIRES_AT = "expires"
        private const val KEY_CID = "cid"
        private const val KEY_ORIGIN = "origin"
    }
}
