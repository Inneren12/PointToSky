package dev.pointtosky.wear.sensors.model

/**
 * Orientation snapshot produced by [dev.pointtosky.wear.sensors.repository.OrientationRepository].
 */
data class OrientationFrame(
    val timestampMs: Long,
    val azimuthDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val forward: FloatArray,
    val rotationMatrix: FloatArray,
    val accuracy: SensorAccuracy,
)

/**
 * Accuracy values reported by virtual or hardware orientation sensors.
 */
enum class SensorAccuracy {
    UNRELIABLE,
    LOW,
    MEDIUM,
    HIGH,
}
