package dev.pointtosky.wear.sensors.util

import java.util.ArrayDeque

class FrameRateAverager(
    private val windowDurationMillis: Long = DEFAULT_WINDOW_MILLIS,
) {
    private val timestamps = ArrayDeque<Long>()

    fun add(timestampNanos: Long): Float? {
        timestamps.addLast(timestampNanos)
        val cutoff = timestampNanos - windowDurationMillis * NANOS_IN_MILLI
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
        val seconds = duration.toDouble() / NANOS_IN_SECOND
        if (seconds <= 0.0) {
            return null
        }
        return ((timestamps.size - 1) / seconds).toFloat()
    }

    fun reset() {
        timestamps.clear()
    }

    companion object {
        private const val DEFAULT_WINDOW_MILLIS: Long = 3_000L
        private const val NANOS_IN_MILLI: Long = 1_000_000L
        private const val NANOS_IN_SECOND: Double = 1_000_000_000.0
    }
}
