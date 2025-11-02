package dev.pointtosky.wear.sensors.model

/**
 * Snapshot of the device orientation delivered by [dev.pointtosky.wear.sensors.OrientationRepository].
 */
data class OrientationFrame(
    val timestampMs: Long,
    val azimuthDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val forward: FloatArray,
    val rotationMatrix: FloatArray,
    val accuracy: SensorAccuracy
) {
    init {
        require(forward.size == 3) {
            "Forward vector must contain exactly 3 components, but was ${forward.size}."
        }
        require(rotationMatrix.size == 9) {
            "Rotation matrix must contain exactly 9 components, but was ${rotationMatrix.size}."
        }
    }
}

/** Accuracy reported by the underlying sensor fusion pipeline. */
enum class SensorAccuracy {
    UNRELIABLE,
    LOW,
    MEDIUM,
    HIGH
}
