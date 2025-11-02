package dev.pointtosky.wear.sensors.model

/**
 * Snapshot of the device orientation in the world (ENU) reference frame.
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
