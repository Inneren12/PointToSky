package dev.pointtosky.core.location.model

data class GeoPoint(
    val latDeg: Double,
    val lonDeg: Double,
)

enum class ProviderType {
    FUSED,
    NETWORK,
    GPS,
    MANUAL,
    REMOTE_PHONE,
    UNKNOWN,
}

enum class AccuracyClass {
    LOW,
    MEDIUM,
    HIGH,
}

data class LocationFix(
    val point: GeoPoint,
    val timeMs: Long,
    val accuracyM: Float?,
    val provider: ProviderType,
    val altitudeM: Double? = null,
    val bearingDeg: Float? = null,
    val speedMps: Float? = null,
)
