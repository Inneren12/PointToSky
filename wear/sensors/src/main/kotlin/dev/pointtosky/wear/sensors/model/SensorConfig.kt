package dev.pointtosky.wear.sensors.model

/**
 * Configuration used when starting the sensor subscriptions.
 */
data class SensorConfig(
    val samplingPeriodUs: Int = 66_666,
    val frameThrottleMs: Long = 66
)
