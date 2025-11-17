package dev.pointtosky.mobile.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.pointtosky.core.location.model.GeoPoint
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class DeviceLocationRepository(
    private val context: Context,
    private val fusedLocationProviderClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context),
    private val externalScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private val permissionState = MutableStateFlow(hasLocationPermission())

    private val locationRequest =
        LocationRequest
            .Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .build()

    val deviceLocationFlow =
        permissionState
            .map { granted ->
                Log.d(TAG, "Location permission changed: $granted")
                granted
            }
            .flatMapLatest { granted ->
                if (!granted) {
                    flowOf<GeoPoint?>(null)
                } else {
                    callbackFlow<GeoPoint?> {
                        // Дополнительный рантайм-чек в том же месте, где вызываем FusedLocationProvider
                        if (!hasLocationPermission()) {
                            Log.w(TAG, "Location permission missing in callbackFlow; emitting null")
                            trySend(null)
                            close()
                            return@callbackFlow
                        }

                        // Сначала регистрируем callback
                        val callback =
                            object : LocationCallback() {
                                override fun onLocationResult(result: LocationResult) {
                                    val location = result.lastLocation
                                    if (location != null) {
                                        val point = GeoPoint(location.latitude, location.longitude)
                                        Log.d(TAG, "Emitting location update: $point")
                                        trySend(point)
                                    }
                                }
                            }

                        try {
                            // lastLocation тоже требует permission, держим под тем же try/catch
                            fusedLocationProviderClient
                                .lastLocation
                                .addOnSuccessListener { location ->
                                    location?.let {
                                        val point = GeoPoint(it.latitude, it.longitude)
                                        Log.d(TAG, "Emitting last known location: $point")
                                        trySend(point)
                                    }
                                }

                            fusedLocationProviderClient.requestLocationUpdates(
                                locationRequest,
                                callback,
                                Looper.getMainLooper(),
                            )
                        } catch (se: SecurityException) {
                            Log.e(TAG, "SecurityException while requesting location updates", se)
                            trySend(null)
                            close(se)
                            return@callbackFlow
                        }

                        awaitClose {
                            fusedLocationProviderClient.removeLocationUpdates(callback)
                        }
                    }
                }
            }
            .distinctUntilChanged()
            .stateIn(
                scope = externalScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                initialValue = null,
            )

    fun onPermissionChanged() {
        val granted = hasLocationPermission()
        if (permissionState.value != granted) {
            permissionState.value = granted
        }
    }

    private fun hasLocationPermission(): Boolean {
        val coarseGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        val fineGranted =
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        return coarseGranted || fineGranted
    }

    companion object {
        private const val UPDATE_INTERVAL_MS = 60_000L
        private const val MIN_UPDATE_INTERVAL_MS = 30_000L
        private const val TAG = "DeviceLocationRepository"
    }
}
