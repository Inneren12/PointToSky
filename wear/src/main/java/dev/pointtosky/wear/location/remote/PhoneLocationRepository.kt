package dev.pointtosky.wear.location.remote

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataClient.OnDataChangedListener
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import dev.pointtosky.core.location.api.LocationConfig
import dev.pointtosky.core.location.api.LocationRepository
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.location.remote.DATA_ITEM_LAST_FIX
import dev.pointtosky.core.location.remote.LocationRequestPayload
import dev.pointtosky.core.location.remote.LocationResponsePayload
import dev.pointtosky.core.location.remote.PATH_LOCATION_REQUEST_ONE
import dev.pointtosky.core.location.remote.PATH_LOCATION_RESPONSE_ONE
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.isActive
import kotlin.io.use

class PhoneLocationRepository(
    context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : LocationRepository, MessageClient.OnMessageReceivedListener, OnDataChangedListener {

    private val appContext = context.applicationContext
    private val messageClient: MessageClient = Wearable.getMessageClient(appContext)
    private val dataClient: DataClient = Wearable.getDataClient(appContext)
    private val nodeClient = Wearable.getNodeClient(appContext)

    private val started = AtomicBoolean(false)
    private val latestFix = AtomicReference<LocationFix?>(null)
    private val pendingRequest = AtomicReference<CompletableDeferred<LocationFix?>?>(null)
    private val configRef = AtomicReference(LocationConfig())
    private val fixesFlow = MutableSharedFlow<LocationFix>(extraBufferCapacity = 16)
    private val lastFixFlow = MutableStateFlow<LocationFix?>(null)
    private var maintainJob: Job? = null

    override val fixes: Flow<LocationFix> = fixesFlow.asSharedFlow()

    val lastKnownFix: Flow<LocationFix?> = lastFixFlow.asStateFlow()

    override suspend fun start(config: LocationConfig) {
        configRef.set(config)
        if (started.compareAndSet(false, true)) {
            messageClient.addListener(this)
            dataClient.addListener(this)
            maintainJob = scope.launch { maintainFreshFixes() }
        }
        scope.launch { ensureFreshFix(force = true) }
    }

    override suspend fun stop() {
        if (!started.compareAndSet(true, false)) return
        maintainJob?.cancelAndJoin()
        maintainJob = null
        messageClient.removeListener(this)
        dataClient.removeListener(this)
        pendingRequest.getAndSet(null)?.complete(null)
    }

    override suspend fun getLastKnown(): LocationFix? {
        val ttlMs = configRef.get().freshTtlMs
        val fix = latestFix.get()
        val now = clock()
        if (fix != null && now - fix.timeMs <= ttlMs) {
            return fix
        }
        return requestFix(ttlMs) ?: latestFix.get()
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_LOCATION_RESPONSE_ONE) return
        val payload = LocationResponsePayload.fromBytes(event.data) ?: return
        handleIncomingFix(payload.fix)
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.use { buffer ->
            for (event in buffer) {
                if (event.type != DataEvent.TYPE_CHANGED) continue
                val item = event.dataItem
                val path = item.uri.path
                if (path != DATA_ITEM_LAST_FIX) continue
                val data = item.data
                val payload = LocationResponsePayload.fromBytes(data) ?: continue
                handleIncomingFix(payload.fix)
            }
        }
    }

    private suspend fun ensureFreshFix(force: Boolean) {
        val ttlMs = configRef.get().freshTtlMs
        val fix = latestFix.get()
        val now = clock()
        val stale = force || fix == null || now - fix.timeMs > ttlMs
        if (stale) {
            requestFix(ttlMs)
        }
    }

    private suspend fun maintainFreshFixes() {
        while (isActive) {
            val ttlMs = configRef.get().freshTtlMs
            val fix = latestFix.get()
            val now = clock()
            val age = if (fix != null) now - fix.timeMs else Long.MAX_VALUE
            if (age > ttlMs) {
                requestFix(ttlMs)
                delay(STALE_RETRY_INTERVAL_MS)
            } else {
                val wait = (ttlMs - age).coerceAtLeast(MIN_REFRESH_INTERVAL_MS)
                delay(wait)
            }
        }
    }

    private suspend fun requestFix(freshTtlMs: Long): LocationFix? {
        pendingRequest.get()?.let { return it.await() }
        val deferred = CompletableDeferred<LocationFix?>()
        if (!pendingRequest.compareAndSet(null, deferred)) {
            return pendingRequest.get()?.await()
        }

        val targetNode = runCatching {
            nodeClient.connectedNodes.await()
        }.getOrNull()?.firstOrNull()

        if (targetNode == null) {
            pendingRequest.compareAndSet(deferred, null)
            deferred.complete(null)
            return null
        }

        val payload = LocationRequestPayload(freshTtlMs = freshTtlMs).toByteArray()
        val sendResult = runCatching {
            messageClient.sendMessage(
                targetNode.id,
                PATH_LOCATION_REQUEST_ONE,
                payload,
            ).await()
        }

        if (sendResult.isFailure) {
            pendingRequest.compareAndSet(deferred, null)
            deferred.complete(null)
            return null
        }

        val fix = withTimeoutOrNull(REQUEST_TIMEOUT_MS) { deferred.await() }
        pendingRequest.compareAndSet(deferred, null)
        if (fix == null) {
            deferred.complete(null)
        }
        return fix
    }

    private fun handleIncomingFix(fix: LocationFix) {
        latestFix.set(fix)
        lastFixFlow.value = fix
        pendingRequest.getAndSet(null)?.complete(fix)
        scope.launch {
            fixesFlow.emit(fix)
        }
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 5_000L
        private const val STALE_RETRY_INTERVAL_MS = 15_000L
        private const val MIN_REFRESH_INTERVAL_MS = 2_000L
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> cont.resume(result) }
    addOnFailureListener { error -> cont.resumeWithException(error) }
}

