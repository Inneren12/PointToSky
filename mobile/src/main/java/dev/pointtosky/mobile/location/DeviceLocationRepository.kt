package dev.pointtosky.mobile.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
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
    private val locationRequest =
        LocationRequest
            .Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_UPDATE_INTERVAL_MS)
            .build()

    val deviceLocationFlow =
        callbackFlow<GeoPoint?> {
            if (!hasLocationPermission()) {
                trySend(null)
                awaitClose { }
                return@callbackFlow
            }

            fusedLocationProviderClient
                .lastLocation
                .addOnSuccessListener { location ->
                    location?.let { trySend(GeoPoint(it.latitude, it.longitude)) }
                }

            val callback =
                object : LocationCallback() {
                    override fun onLocationResult(result: LocationResult) {
                        val location = result.lastLocation
                        if (location != null) {
                            trySend(GeoPoint(location.latitude, location.longitude))
                        }
                    }
                }

            fusedLocationProviderClient.requestLocationUpdates(
                locationRequest,
                callback,
                Looper.getMainLooper(),
            )

            awaitClose { fusedLocationProviderClient.removeLocationUpdates(callback) }
        }
            .distinctUntilChanged()
            .stateIn(
                scope = externalScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                initialValue = null,
            )

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
    }
}
