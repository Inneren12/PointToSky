package dev.pointtosky.wear.sensors.orientation

import dev.pointtosky.core.logging.FrameTraceMode
import dev.pointtosky.core.logging.LogBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

internal class OrientationFrameLogger(
    private val source: OrientationSource,
    private val scope: CoroutineScope,
    private val frameTraceMode: StateFlow<FrameTraceMode>,
    private val onFpsChanged: (Float?) -> Unit,
) {
    private val channel = Channel<OrientationFrame>(capacity = CHANNEL_CAPACITY)
    private val fpsWindow = ArrayDeque<Long>()
    private val summaryFrames = mutableListOf<OrientationFrame>()
    private val summaryFps = mutableListOf<Double>()

    @Volatile
    private var mode: FrameTraceMode = frameTraceMode.value
    private var lastSummaryTimestampNs: Long = 0L
    private var previousFrameTimestampNs: Long? = null
    private var lastFpsLogTimestampNs: Long = 0L

    init {
        scope.launch {
            frameTraceMode.collect { newMode ->
                mode = newMode
                resetState()
            }
        }
        scope.launch {
            for (frame in channel) {
                handleFrame(frame)
            }
        }
    }

    fun submit(frame: OrientationFrame) {
        channel.trySend(frame)
    }

    fun reset() {
        scope.launch {
            resetState()
        }
    }

    private fun handleFrame(frame: OrientationFrame) {
        updateFps(frame)
        when (mode) {
            FrameTraceMode.OFF -> Unit
            FrameTraceMode.SUMMARY_1HZ -> processSummary(frame)
            FrameTraceMode.FULL_15HZ -> logFrame(frame)
        }
    }

    private fun updateFps(frame: OrientationFrame) {
        fpsWindow.addLast(frame.timestampNanos)
        val cutoff = frame.timestampNanos - FPS_WINDOW_NS
        while (fpsWindow.isNotEmpty() && fpsWindow.first() < cutoff) {
            fpsWindow.removeFirst()
        }
        if (fpsWindow.size >= 2) {
            val durationSec = (fpsWindow.last() - fpsWindow.first()).toDouble() / NANOS_IN_SECOND
            val fps = if (durationSec > 0) ((fpsWindow.size - 1) / durationSec) else 0.0
            onFpsChanged(fps.toFloat())
            if (lastFpsLogTimestampNs == 0L || frame.timestampNanos - lastFpsLogTimestampNs >= FPS_LOG_INTERVAL_NS) {
                LogBus.d(
                    tag = "Sensors",
                    msg = "fps",
                    payload = mapOf(
                        "fps" to fps,
                        "source" to source.name,
                    ),
                )
                lastFpsLogTimestampNs = frame.timestampNanos
            }
        } else {
            onFpsChanged(null)
        }
    }

    private fun processSummary(frame: OrientationFrame) {
        if (lastSummaryTimestampNs == 0L) {
            lastSummaryTimestampNs = frame.timestampNanos
        }
        summaryFrames.add(frame)
        previousFrameTimestampNs?.let { previous ->
            val deltaNs = frame.timestampNanos - previous
            if (deltaNs > 0) {
                summaryFps.add(NANOS_IN_SECOND / deltaNs.toDouble())
            }
        }
        previousFrameTimestampNs = frame.timestampNanos

        if (frame.timestampNanos - lastSummaryTimestampNs >= SUMMARY_WINDOW_NS) {
            val framesCopy = summaryFrames.toList()
            val fpsCopy = summaryFps.toList()
            if (framesCopy.isNotEmpty()) {
                logSummary(framesCopy, fpsCopy)
            }
            summaryFrames.clear()
            summaryFps.clear()
            summaryFrames.add(frame)
            lastSummaryTimestampNs = frame.timestampNanos
            previousFrameTimestampNs = frame.timestampNanos
        }
    }

    private fun logSummary(frames: List<OrientationFrame>, fpsValues: List<Double>) {
        val meanAzimuth = frames.map { it.azimuthDeg.toDouble() }.average()
        val meanPitch = frames.map { it.pitchDeg.toDouble() }.average()
        val meanRoll = frames.map { it.rollDeg.toDouble() }.average()
        val fpsMean = fpsValues.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        val fpsStd = if (fpsValues.isNotEmpty()) {
            val mean = fpsMean
            sqrt(fpsValues.sumOf { (it - mean) * (it - mean) } / fpsValues.size)
        } else {
            0.0
        }
        val minAccuracy = frames.minByOrNull { it.accuracy.ordinal }?.accuracy
        val maxAccuracy = frames.maxByOrNull { it.accuracy.ordinal }?.accuracy

        LogBus.d(
            tag = "Sensors",
            msg = "frame_summary",
            payload = mapOf(
                "source" to source.name,
                "count" to frames.size,
                "azimuthMean" to meanAzimuth,
                "pitchMean" to meanPitch,
                "rollMean" to meanRoll,
                "fpsMean" to fpsMean,
                "fpsStd" to fpsStd,
                "accuracyMin" to minAccuracy,
                "accuracyMax" to maxAccuracy,
            ),
        )
    }

    private fun logFrame(frame: OrientationFrame) {
        LogBus.d(
            tag = "Sensors",
            msg = "frame",
            payload = mapOf(
                "source" to source.name,
                "timestampNs" to frame.timestampNanos,
                "azimuthDeg" to frame.azimuthDeg,
                "pitchDeg" to frame.pitchDeg,
                "rollDeg" to frame.rollDeg,
                "accuracy" to frame.accuracy,
                "forward" to frame.forward.map { it.toDouble() },
                "rotationMatrix" to frame.rotationMatrix.map { it.toDouble() },
            ),
        )
    }

    private fun resetState() {
        summaryFrames.clear()
        summaryFps.clear()
        fpsWindow.clear()
        previousFrameTimestampNs = null
        lastSummaryTimestampNs = 0L
        lastFpsLogTimestampNs = 0L
        onFpsChanged(null)
    }

    companion object {
        private const val CHANNEL_CAPACITY = 128
        private val SUMMARY_WINDOW_NS = TimeUnit.SECONDS.toNanos(1)
        private val FPS_WINDOW_NS = TimeUnit.SECONDS.toNanos(3)
        private val FPS_LOG_INTERVAL_NS = TimeUnit.SECONDS.toNanos(5)
        private const val NANOS_IN_SECOND = 1_000_000_000.0
    }
}
