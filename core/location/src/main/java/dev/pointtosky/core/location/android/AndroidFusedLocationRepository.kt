package dev.pointtosky.core.location.android

import android.annotation.SuppressLint
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
import com.google.android.gms.tasks.Task
import dev.pointtosky.core.location.api.LocationConfig
import dev.pointtosky.core.location.api.LocationRepository
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.location.model.ProviderType
import java.util.Locale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AndroidFusedLocationRepository(
    context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context.applicationContext),
    private val timeProvider: () -> Long = System::currentTimeMillis,
) : LocationRepository {

    private val scope = CoroutineScope(SupervisorJob() + io)
    private val mutex = Mutex()
    private var updatesJob: Job? = null

    private val _fixes = MutableSharedFlow<LocationFix>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val fixes: Flow<LocationFix> = _fixes.asSharedFlow()

    override suspend fun start(config: LocationConfig) {
        mutex.withLock {
            if (updatesJob?.isActive == true) return

            updatesJob = scope.launch {
                emitFreshLastKnown(config)

                createLocationUpdatesFlow(config)
                    .catch { error ->
                        if (error !is SecurityException) throw error
                    }
                    .collect { fix -> _fixes.emit(fix) }
            }
        }
    }

    override suspend fun stop() {
        mutex.withLock {
            val job = updatesJob ?: return
            job.cancel()
            try {
                job.join()
            } finally {
                updatesJob = null
            }
        }
    }

    override suspend fun getLastKnown(): LocationFix? = safeGetLastLocation()?.toLocationFixInternal()

    private suspend fun emitFreshLastKnown(config: LocationConfig) {
        val lastKnown = safeGetLastLocation()
        val now = timeProvider()
        if (lastKnown != null && lastKnown.isFresh(now, config.freshTtlMs)) {
            val fix = lastKnown.toLocationFixInternal()
            if (fix != null) {
                _fixes.emit(fix)
            }
        }
    }

    private fun createLocationUpdatesFlow(config: LocationConfig): Flow<LocationFix> {
        val request = createLocationRequest(config)
        val looper = Looper.getMainLooper()

        val rawFlow: Flow<LocationFix> = callbackFlow {
            val callback = object : LocationCallback() {
                override fun onLocationResult(result: LocationResult) {
                    for (location in result.locations) {
                        val fix = location.toLocationFixInternal() ?: continue
                        trySend(fix)
                    }
                }
            }

            var registered = false
            try {
                requestLocationUpdates(request, callback, looper)
                registered = true
            } catch (security: SecurityException) {
                close(security)
            } catch (throwable: Throwable) {
                close(throwable)
            }

            awaitClose {
                if (registered) {
                    removeLocationUpdatesSafely(callback)
                }
            }
        }

        return rawFlow.applyThrottle(config.throttleMs)
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(
        request: LocationRequest,
        callback: LocationCallback,
        looper: Looper,
    ) {
        fusedClient.requestLocationUpdates(request, callback, looper)
    }

    private fun removeLocationUpdatesSafely(callback: LocationCallback) {
        try {
            fusedClient.removeLocationUpdates(callback)
        } catch (_: SecurityException) {
            // ignore
        }
    }

    private fun createLocationRequest(config: LocationConfig): LocationRequest {
        return LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, config.minUpdateIntervalMs)
            .setMinUpdateIntervalMillis(config.minUpdateIntervalMs)
            .setMinUpdateDistanceMeters(config.minDistanceM)
            .build()
    }

    private suspend fun safeGetLastLocation(): Location? = withContext(io) {
        try {
            withTimeoutOrNull(LAST_KNOWN_TIMEOUT_MS) {
                fusedClient.lastLocation.awaitOrNull()
            }
        } catch (_: SecurityException) {
            null
        }
    }

    companion object {
        private const val LAST_KNOWN_TIMEOUT_MS = 2_000L
    }
}

internal fun Location.isFresh(nowMs: Long, ttlMs: Long): Boolean {
    if (time <= 0L) return false
    val age = nowMs - time
    if (age < 0) return false
    return age <= ttlMs
}

private fun Double.isFinite(): Boolean = !isNaN() && !isInfinite()

internal fun Location.toLocationFixInternal(): LocationFix? {
    val lat = latitude.normalizeLatitude()
    val lon = longitude.normalizeLongitude()
    val providerType = provider.toProviderType()
    return LocationFix(
        point = GeoPoint(lat, lon),
        timeMs = time,
        accuracyM = accuracy.takeIf { hasAccuracy() && it.isFinite() },
        provider = providerType,
        altitudeM = altitude.takeIf { hasAltitude() && it.isFinite() },
        bearingDeg = bearing.takeIf { hasBearing() && it.isFinite() },
        speedMps = speed.takeIf { hasSpeed() && it.isFinite() },
    )
}

private fun Float.isFinite(): Boolean = !isNaN() && !isInfinite()

private fun Double.normalizeLatitude(): Double {
    if (!isFinite()) return 0.0
    return coerceIn(-90.0, 90.0)
}

private fun Double.normalizeLongitude(): Double {
    if (!isFinite()) return 0.0
    var value = this
    while (value <= -180.0) value += 360.0
    while (value > 180.0) value -= 360.0
    return value
}

private fun String?.toProviderType(): ProviderType {
    val normalized = this?.lowercase(Locale.US)
    return when (normalized) {
        LocationManager.GPS_PROVIDER -> ProviderType.GPS
        LocationManager.NETWORK_PROVIDER -> ProviderType.NETWORK
        "fused" -> ProviderType.FUSED
        else -> ProviderType.UNKNOWN
    }
}

internal fun <T> Flow<T>.applyThrottle(throttleMs: Long): Flow<T> {
    return if (throttleMs > 0L) {
        sample(throttleMs)
    } else {
        this
    }
}

private suspend fun <T> Task<T>.awaitOrNull(): T? = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        if (continuation.isActive) {
            continuation.resume(result)
        }
    }
    addOnFailureListener {
        if (continuation.isActive) {
            continuation.resume(null)
        }
    }
    addOnCanceledListener {
        if (continuation.isActive) {
            continuation.resume(null)
        }
    }
    continuation.invokeOnCancellation {
        try {
            cancel()
        } catch (_: Exception) {
            // ignore
        }
    }
}
