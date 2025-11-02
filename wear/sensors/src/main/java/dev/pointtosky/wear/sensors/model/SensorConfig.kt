package dev.pointtosky.wear.sensors.model

/**
 * Configuration that controls how often orientation frames are produced.
 */
data class SensorConfig(
    val samplingPeriodUs: Int = DEFAULT_SAMPLING_PERIOD_US,
    val frameThrottleMs: Long = DEFAULT_FRAME_THROTTLE_MS,
) {
    companion object {
        private const val DEFAULT_SAMPLING_PERIOD_US: Int = 66_666
        private const val DEFAULT_FRAME_THROTTLE_MS: Long = 66
    }
}
