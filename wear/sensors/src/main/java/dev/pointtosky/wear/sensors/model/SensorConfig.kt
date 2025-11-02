package dev.pointtosky.wear.sensors.model

/**
 * Configuration for starting sensor data collection.
 */
data class SensorConfig(
    val samplingPeriodUs: Int = DEFAULT_SAMPLING_PERIOD_US,
    val frameThrottleMs: Long = DEFAULT_FRAME_THROTTLE_MS,
) {
    companion object {
        const val DEFAULT_SAMPLING_PERIOD_US: Int = 66_666
        const val DEFAULT_FRAME_THROTTLE_MS: Long = 66L
    }
}
