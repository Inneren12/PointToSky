package dev.pointtosky.core.location.api

import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.model.LocationFix
import kotlinx.coroutines.flow.Flow

data class LocationConfig(
    val priority: LocationPriority = LocationPriority.BALANCED,
    val minUpdateIntervalMs: Long = 10_000,
    val minDistanceM: Float = 50f,
    val throttleMs: Long = 10_000,
    val freshTtlMs: Long = 120_000,
)

enum class LocationPriority {
    PASSIVE,
    BALANCED,
    HIGH_ACCURACY,
}

interface LocationRepository {
    val fixes: Flow<LocationFix>

    suspend fun start(config: LocationConfig = LocationConfig())

    suspend fun stop()

    suspend fun getLastKnown(): LocationFix?
}

interface LocationOrchestrator : LocationRepository {
    suspend fun setManual(point: GeoPoint?)

    suspend fun preferPhoneFallback(enabled: Boolean)
}
