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
    /**
     * Like [fixes] but nullable: emits each fix while a source is available, and emits null when
     * none is (permission revoked, manual cleared with no fallback, provider stopped). Unlike
     * [fixes] — a non-null Flow that merely goes silent on loss — this lets consumers react to
     * location LOSS. It does NOT emit null merely because the active provider is quiet (e.g. a
     * stationary user): the last fix is retained until a source actually becomes unavailable.
     */
    val currentFix: Flow<LocationFix?>

    suspend fun setManual(point: GeoPoint?)

    suspend fun preferPhoneFallback(enabled: Boolean)
}
