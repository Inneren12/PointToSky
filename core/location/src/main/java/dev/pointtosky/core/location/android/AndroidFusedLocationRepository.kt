package dev.pointtosky.core.location.android

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.pointtosky.core.location.api.LocationConfig
import dev.pointtosky.core.location.api.LocationPriority
import dev.pointtosky.core.location.api.LocationRepository
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.location.model.ProviderType
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.BufferOverflow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.throttleLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine

class AndroidFusedLocationRepository(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val timeProvider: () -> Long = System::currentTimeMillis,
    private val delegate: FusedClientDelegate = RealFusedClientDelegate(
        LocationServices.getFusedLocationProviderClient(context.applicationContext)
    ),
) : LocationRepository {

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val mutex = Mutex()
    private val throttleMs = MutableStateFlow<Long?>(null)
    private val rawFixes = MutableSharedFlow<LocationFix>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val latestFix = AtomicReference<LocationFix?>(null)

    override val fixes: Flow<LocationFix> = throttleMs.flatMapLatest { interval ->
        when {
            interval == null -> emptyFlow()
            interval <= 0L -> rawFixes
            else -> rawFixes.throttleLatest(interval)
        }
    }

    private var updatesJob: Job? = null
    private var started = false

    override suspend fun start(config: LocationConfig) {
        mutex.withLock {
            throttleMs.value = config.throttleMs
            if (started) return
            started = true

            val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .setMinUpdateIntervalMillis(config.minUpdateIntervalMs.coerceAtLeast(0L))
                .setMinUpdateDistanceMeters(config.minDistanceM)
                .setGranularity(LocationRequest.GRANULARITY_PERMISSION_LEVEL)
                .build()

            updatesJob = scope.launch {
                try {
                    delegate.locationUpdates(request).collect { fix ->
                        latestFix.set(fix)
                        rawFixes.emit(fix)
                    }
                } catch (_: SecurityException) {
                    mutex.withLock {
                        started = false
                        updatesJob = null
                    }
                }
            }
        }

        scope.launch {
            val freshFix = fetchFreshLastKnown(config.freshTtlMs)
            if (freshFix != null) {
                latestFix.set(freshFix)
                rawFixes.emit(freshFix)
            }
        }
    }

    override suspend fun stop() {
        mutex.withLock {
            if (!started) return
            started = false
            updatesJob?.cancelAndJoin()
            updatesJob = null
        }
    }

    override suspend fun getLastKnown(): LocationFix? {
        latestFix.get()?.let { return it }
        return runCatching { fetchLastKnown() }.getOrNull()
    }

    suspend fun getCurrentLocation(
        timeoutMs: Long,
        priority: LocationPriority = LocationPriority.BALANCED,
    ): LocationFix? {
        val fusedPriority = when (priority) {
            LocationPriority.PASSIVE -> Priority.PRIORITY_PASSIVE
            LocationPriority.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
            LocationPriority.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
        }
        return try {
            delegate.currentLocation(timeoutMs, fusedPriority)?.toLocationFix()
        } catch (_: SecurityException) {
            null
        }
    }

    private suspend fun fetchFreshLastKnown(ttlMs: Long): LocationFix? {
        val fix = runCatching { fetchLastKnown() }.getOrNull() ?: return null
        val isFresh = timeProvider.invoke() - fix.timeMs <= ttlMs
        return if (isFresh) fix else null
    }

    private suspend fun fetchLastKnown(): LocationFix? {
        val location = delegate.lastLocation() ?: return null
        return location.toLocationFix()
    }

    private companion object {
        private const val LAST_LOCATION_TIMEOUT_MS = 2_000L
    }

    interface FusedClientDelegate {
        fun locationUpdates(request: LocationRequest): Flow<LocationFix>

        suspend fun lastLocation(): Location?

        suspend fun currentLocation(timeoutMs: Long, priority: Int): Location?
    }

    class RealFusedClientDelegate(
        private val client: FusedLocationProviderClient,
    ) : FusedClientDelegate {
        override fun locationUpdates(request: LocationRequest): Flow<LocationFix> = callbackFlow {
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    for (location in result.locations) {
                        trySend(location.toLocationFix())
                    }
                }
            }
            try {
                client.requestLocationUpdates(request, callback, Looper.getMainLooper())
            } catch (se: SecurityException) {
                close(se)
                return@callbackFlow
            }
            awaitClose {
                runCatching { client.removeLocationUpdates(callback) }
            }
        }

        override suspend fun lastLocation(): Location? {
            return try {
                withTimeoutOrNull(LAST_LOCATION_TIMEOUT_MS) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val task = client.lastLocation
                        task.addOnSuccessListener { cont.resume(it) }
                        task.addOnFailureListener { err -> cont.resumeWithException(err) }
                        cont.invokeOnCancellation { }
                    }
                }
            } catch (se: SecurityException) {
                null
            }
        }

        override suspend fun currentLocation(timeoutMs: Long, priority: Int): Location? {
            return try {
                withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val tokenSource = CancellationTokenSource()
                        val task = client.getCurrentLocation(priority, tokenSource.token)
                        task.addOnSuccessListener { location ->
                            cont.resume(location)
                        }
                        task.addOnFailureListener { error ->
                            cont.resumeWithException(error)
                        }
                        cont.invokeOnCancellation { tokenSource.cancel() }
                    }
                }
            } catch (se: SecurityException) {
                null
            }
        }
    }
}

internal fun Location.toLocationFix(): LocationFix = LocationFix(
    point = GeoPoint(latDeg = latitude, lonDeg = longitude),
    timeMs = time,
    accuracyM = if (hasAccuracy()) accuracy else null,
    provider = provider.toProviderType(),
    altitudeM = if (hasAltitude()) altitude else null,
    bearingDeg = if (hasBearing()) bearing else null,
    speedMps = if (hasSpeed()) speed else null,
)

private fun String?.toProviderType(): ProviderType = when (this?.lowercase(Locale.US)) {
    LocationManager.GPS_PROVIDER -> ProviderType.GPS
    LocationManager.NETWORK_PROVIDER -> ProviderType.NETWORK
    "fused" -> ProviderType.FUSED
    else -> ProviderType.UNKNOWN
}
