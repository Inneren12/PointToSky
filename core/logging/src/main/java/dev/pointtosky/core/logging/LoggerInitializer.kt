package dev.pointtosky.core.logging

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Process
import java.io.File

object LoggerInitializer {
    fun init(context: Context, isDebug: Boolean, deviceInfo: DeviceInfo): Logger {
        return init(context, isDebug, diagnosticsLogsEnabled = false, deviceInfo = deviceInfo)
    }

    fun init(
        context: Context,
        isDebug: Boolean,
        diagnosticsLogsEnabled: Boolean,
        deviceInfo: DeviceInfo
    ): Logger {
        val logsDirectory = File(context.filesDir, "logs")
        if (!logsDirectory.exists()) {
            logsDirectory.mkdirs()
        }
        val diagnosticsEnabled = isDebug || diagnosticsLogsEnabled || deviceInfo.diagnosticsEnabled
        val config = LoggerConfig(
            logsDirectory = logsDirectory,
            diagnosticsEnabled = diagnosticsEnabled
        )
        val ringBuffer = RingBufferSink(config.ringBufferCapacity)
        val sinks = mutableListOf<LogSink>(ringBuffer)
        if (diagnosticsEnabled && logsDirectory.canWrite()) {
            val fileManager = LogFileManager(config)
            val fileSink = FileLogSink(config, fileManager)
            sinks.add(fileSink)
        }
        val sink = if (sinks.size == 1) sinks.first() else MultiSink(sinks)
        val writer = LogWriter(sink, flushIntervalMs = config.flushIntervalMs)
        val process = resolveProcess(context)
        val enrichedDeviceInfo = deviceInfo.copy(
            isDebug = isDebug,
            diagnosticsEnabled = diagnosticsEnabled
        )
        LogBus.install(writer, config, enrichedDeviceInfo, process, ringBuffer)
        return LogBus
    }

    private fun resolveProcess(context: Context): ProcessSnapshot {
        val processName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            context.applicationInfo.processName
        }
        return ProcessSnapshot(
            pid = Process.myPid(),
            processName = processName ?: context.packageName
        )
    }
}
