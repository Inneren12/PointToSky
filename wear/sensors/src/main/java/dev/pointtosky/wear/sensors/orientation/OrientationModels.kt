package dev.pointtosky.wear.sensors.orientation

import android.hardware.SensorManager

data class OrientationFrame(
    val timestampNanos: Long,
    val azimuthDeg: Float,
    val pitchDeg: Float,
    val rollDeg: Float,
    val forward: FloatArray,
    val accuracy: OrientationAccuracy,
    val rotationMatrix: FloatArray,
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

enum class OrientationSource {
    ROTATION_VECTOR,
    ACCEL_MAG,
}

enum class ScreenRotation(
    val degrees: Int,
    val remapAxisX: Int,
    val remapAxisY: Int,
) {
    ROT_0(
        degrees = 0,
        remapAxisX = SensorManager.AXIS_X,
        remapAxisY = SensorManager.AXIS_Y,
    ),
    ROT_90(
        degrees = 90,
        remapAxisX = SensorManager.AXIS_Y,
        remapAxisY = SensorManager.AXIS_MINUS_X,
    ),
    ROT_180(
        degrees = 180,
        remapAxisX = SensorManager.AXIS_MINUS_X,
        remapAxisY = SensorManager.AXIS_MINUS_Y,
    ),
    ROT_270(
        degrees = 270,
        remapAxisX = SensorManager.AXIS_MINUS_Y,
        remapAxisY = SensorManager.AXIS_X,
    ),
    ;

    companion object {
        fun fromDegrees(degrees: Int): ScreenRotation {
            val normalized = ((degrees % 360) + 360) % 360
            return entries.firstOrNull { it.degrees == normalized } ?: ROT_0
        }
    }
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
