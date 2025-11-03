package dev.pointtosky.wear.sensors.util

import kotlin.collections.ArrayDeque

class FrameRateAverager(
    windowDurationMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private val windowDurationNanos: Long = windowDurationMillis * NANOS_IN_MILLI
    private val timestamps = ArrayDeque<Long>()

    fun addSample(timestampNanos: Long): Float? {
        if (timestampNanos <= 0L) {
            return null
        }

        timestamps.addLast(timestampNanos)

        val cutoff = timestampNanos - windowDurationNanos
        while (timestamps.isNotEmpty() && timestamps.first() < cutoff) {
            timestamps.removeFirst()
        }

        if (timestamps.size < 2) {
            return null
        }

        val duration = timestamps.last() - timestamps.first()
        if (duration <= 0L) {
            return null
        }

        val frames = timestamps.size - 1
        val fps = frames.toDouble() / (duration.toDouble() / NANOS_IN_SECOND)
        return fps.toFloat()
    }

    fun reset() {
        timestamps.clear()
    }

    companion object {
        const val DEFAULT_WINDOW_MILLIS: Long = 3_000L
        private const val NANOS_IN_MILLI = 1_000_000L
        private const val NANOS_IN_SECOND = 1_000_000_000.0
    }
}
