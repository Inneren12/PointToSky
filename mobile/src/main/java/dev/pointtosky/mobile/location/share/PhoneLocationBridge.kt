package dev.pointtosky.mobile.location.share

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import dev.pointtosky.core.location.android.AndroidFusedLocationRepository
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.location.model.ProviderType
import dev.pointtosky.core.location.prefs.LocationPrefs
import dev.pointtosky.core.location.remote.LocationRequestPayload
import dev.pointtosky.core.location.remote.LocationResponsePayload
import dev.pointtosky.core.location.remote.PATH_LOCATION_REQUEST_ONE
import dev.pointtosky.core.location.remote.PATH_LOCATION_RESPONSE_ONE
import dev.pointtosky.mobile.logging.MobileLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PhoneLocationBridge(
    context: Context,
    private val locationPrefs: LocationPrefs,
    private val fusedRepository: AndroidFusedLocationRepository = AndroidFusedLocationRepository(context),
    private val clock: () -> Long = System::currentTimeMillis,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : MessageClient.OnMessageReceivedListener {

    private val appContext = context.applicationContext
    private val messageClient: MessageClient = Wearable.getMessageClient(appContext)
    private val started = AtomicBoolean(false)

    private val latestRequest = MutableStateFlow<RequestSnapshot?>(null)
    private val latestResponse = MutableStateFlow<ResponseSnapshot?>(null)
    private val shareEnabled = MutableStateFlow(false)

    val state: StateFlow<PhoneLocationBridgeState> = combine(
        shareEnabled,
        latestRequest,
        latestResponse,
    ) { share, request, response ->
        PhoneLocationBridgeState(
            shareEnabled = share,
            lastRequest = request,
            lastResponse = response,
        )
    }.stateIn(
        scope,
        SharingStarted.Eagerly,
        PhoneLocationBridgeState.Empty,
    )

    init {
        scope.launch {
            locationPrefs.shareLocationWithWatchFlow.collect { enabled ->
                shareEnabled.value = enabled
            }
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true)) return
        messageClient.addListener(this)
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        messageClient.removeListener(this)
    }

    suspend fun setShareEnabled(enabled: Boolean) {
        locationPrefs.setShareLocationWithWatch(enabled)
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_LOCATION_REQUEST_ONE) return
        val payload = LocationRequestPayload.fromBytes(event.data) ?: return
        MobileLog.bridgeRecv(path = event.path, cid = null, nodeId = event.sourceNodeId)
        scope.launch {
            handleLocationRequest(event.sourceNodeId, payload)
        }
    }

    private suspend fun handleLocationRequest(nodeId: String, payload: LocationRequestPayload) {
        val requestTime = clock()
        latestRequest.value = RequestSnapshot(
            timestampMs = requestTime,
            freshTtlMs = payload.freshTtlMs,
        )

        if (!shareEnabled.value) {
            latestResponse.value = ResponseSnapshot(
                timestampMs = clock(),
                status = ResponseStatus.SHARING_DISABLED,
                provider = null,
                accuracyM = null,
            )
            return
        }

        if (!hasLocationPermission()) {
            latestResponse.value = ResponseSnapshot(
                timestampMs = clock(),
                status = ResponseStatus.PERMISSION_DENIED,
                provider = null,
                accuracyM = null,
            )
            return
        }

        val locationResult = fetchBestLocation(payload.freshTtlMs)
        when (locationResult) {
            is FetchResult.Success -> {
                val responsePayload = LocationResponsePayload(
                    fix = locationResult.fix,
                    rawProvider = locationResult.rawProvider,
                )
                val responseBytes = responsePayload.toByteArray()
                MobileLog.bridgeSend(
                    path = PATH_LOCATION_RESPONSE_ONE,
                    cid = null,
                    nodeId = nodeId,
                    attempt = 1,
                    payloadBytes = responseBytes.size,
                )
                val sendResult = runCatching {
                    messageClient.sendMessage(
                        nodeId,
                        PATH_LOCATION_RESPONSE_ONE,
                        responseBytes,
                    ).await()
                }
                latestResponse.value = if (sendResult.isSuccess) {
                    ResponseSnapshot(
                        timestampMs = clock(),
                        status = ResponseStatus.SUCCESS,
                        provider = locationResult.rawProvider,
                        accuracyM = locationResult.fix.accuracyM,
                    )
                } else {
                    val error = sendResult.exceptionOrNull()
                    MobileLog.bridgeError(
                        path = PATH_LOCATION_RESPONSE_ONE,
                        cid = null,
                        nodeId = nodeId,
                        error = error?.message,
                    )
                    ResponseSnapshot(
                        timestampMs = clock(),
                        status = ResponseStatus.SEND_FAILED,
                        provider = locationResult.rawProvider,
                        accuracyM = locationResult.fix.accuracyM,
                    )
                }
            }

            FetchResult.NotAvailable -> {
                latestResponse.value = ResponseSnapshot(
                    timestampMs = clock(),
                    status = ResponseStatus.LOCATION_UNAVAILABLE,
                    provider = null,
                    accuracyM = null,
                )
            }
        }
    }

    private fun hasLocationPermission(): Boolean {
        val coarseGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val fineGranted = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        return coarseGranted || fineGranted
    }

    private suspend fun fetchBestLocation(freshTtlMs: Long): FetchResult {
        val lastKnown = fusedRepository.getLastKnown()
        val now = clock()
        if (lastKnown != null && now - lastKnown.timeMs <= freshTtlMs) {
            return FetchResult.Success(
                fix = lastKnown,
                rawProvider = lastKnown.provider.toRawProvider(),
            )
        }

        val current = fusedRepository.getCurrentLocation(timeoutMs = CURRENT_LOCATION_TIMEOUT_MS)
        if (current != null) {
            return FetchResult.Success(
                fix = current,
                rawProvider = current.provider.toRawProvider(),
            )
        }

        if (lastKnown != null) {
            return FetchResult.Success(
                fix = lastKnown,
                rawProvider = lastKnown.provider.toRawProvider(),
            )
        }
        return FetchResult.NotAvailable
    }

    private fun ProviderType.toRawProvider(): String = when (this) {
        ProviderType.FUSED -> "FUSED"
        ProviderType.NETWORK -> "NETWORK"
        ProviderType.GPS -> "GPS"
        ProviderType.MANUAL -> "MANUAL"
        ProviderType.REMOTE_PHONE -> "REMOTE_PHONE"
        ProviderType.UNKNOWN -> "UNKNOWN"
    }

    private sealed class FetchResult {
        data class Success(val fix: LocationFix, val rawProvider: String) : FetchResult()
        data object NotAvailable : FetchResult()
    }

    data class PhoneLocationBridgeState(
        val shareEnabled: Boolean,
        val lastRequest: RequestSnapshot?,
        val lastResponse: ResponseSnapshot?,
    ) {
        companion object {
            val Empty = PhoneLocationBridgeState(
                shareEnabled = false,
                lastRequest = null,
                lastResponse = null,
            )
        }
    }

    data class RequestSnapshot(
        val timestampMs: Long,
        val freshTtlMs: Long,
    )

    data class ResponseSnapshot(
        val timestampMs: Long,
        val status: ResponseStatus,
        val provider: String?,
        val accuracyM: Float?,
    )

    enum class ResponseStatus {
        SUCCESS,
        SHARING_DISABLED,
        PERMISSION_DENIED,
        LOCATION_UNAVAILABLE,
        SEND_FAILED,
    }

    companion object {
        private const val CURRENT_LOCATION_TIMEOUT_MS = 5_000L
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result -> cont.resume(result) }
    addOnFailureListener { error -> cont.resumeWithException(error) }
}
