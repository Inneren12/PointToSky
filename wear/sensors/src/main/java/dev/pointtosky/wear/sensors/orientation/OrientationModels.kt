package dev.pointtosky.wear.sensors.orientation

import android.hardware.SensorManager

data class OrientationFrame(
    val timestampNanos: Long,
    val azimuthDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val forward: FloatArray,
    val accuracy: OrientationAccuracy,
)

data class OrientationZero(
    val azimuthOffsetDeg: Float = 0f,
)

enum class OrientationAccuracy {
    UNRELIABLE,
    LOW,
    MEDIUM,
    HIGH,
}

enum class ScreenRotation(
    val remapAxisX: Int,
    val remapAxisY: Int,
) {
    ROT_0(SensorManager.AXIS_X, SensorManager.AXIS_Y),
}

data class OrientationRepositoryConfig(
    val samplingPeriodUs: Int = SensorManager.SENSOR_DELAY_GAME,
    val frameThrottleMs: Long = DEFAULT_FRAME_THROTTLE_MS,
    val screenRotation: ScreenRotation = ScreenRotation.ROT_0,
) {
    companion object {
        const val DEFAULT_FRAME_THROTTLE_MS: Long = 66L
    }
}
