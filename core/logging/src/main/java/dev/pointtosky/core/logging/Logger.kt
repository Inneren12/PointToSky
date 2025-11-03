package dev.pointtosky.core.logging

import android.os.Looper
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.runBlocking

interface Logger {
    fun v(tag: String, msg: String, payload: Map<String, Any?> = emptyMap())
    fun d(tag: String, msg: String, payload: Map<String, Any?> = emptyMap())
    fun i(tag: String, msg: String, payload: Map<String, Any?> = emptyMap())
    fun w(tag: String, msg: String, err: Throwable? = null, payload: Map<String, Any?> = emptyMap())
    fun e(tag: String, msg: String, err: Throwable? = null, payload: Map<String, Any?> = emptyMap())
    fun event(name: String, payload: Map<String, Any?> = emptyMap())
}

object LogBus : Logger {
    private val writerRef = AtomicReference<LogWriter?>()
    private val configRef = AtomicReference<LoggerConfig?>()
    private val deviceInfoRef = AtomicReference<DeviceInfo?>()
    private val processRef = AtomicReference<ProcessSnapshot?>()
    private val ringBufferRef = AtomicReference<RingBufferSink?>()
    private val frameTraceModeState = MutableStateFlow(FrameTraceMode.OFF)

    fun install(
        writer: LogWriter,
        config: LoggerConfig,
        deviceInfo: DeviceInfo,
        process: ProcessSnapshot,
        ringBufferSink: RingBufferSink
    ) {
        writerRef.getAndSet(writer)?.let { runBlocking { it.shutdown() } }
        configRef.set(config)
        deviceInfoRef.set(deviceInfo)
        processRef.set(process)
        ringBufferRef.set(ringBufferSink)
        frameTraceModeState.value = config.frameTraceMode
    }

    fun reset() {
        writerRef.getAndSet(null)?.let { runBlocking { it.shutdown() } }
        configRef.set(null)
        deviceInfoRef.set(null)
        processRef.set(null)
        ringBufferRef.set(null)
        frameTraceModeState.value = FrameTraceMode.OFF
    }

    fun config(): LoggerConfig? = configRef.get()

    override fun v(tag: String, msg: String, payload: Map<String, Any?>) {
        log(LogLevel.VERBOSE, tag, msg, null, payload, null)
    }

    override fun d(tag: String, msg: String, payload: Map<String, Any?>) {
        log(LogLevel.DEBUG, tag, msg, null, payload, null)
    }

    override fun i(tag: String, msg: String, payload: Map<String, Any?>) {
        log(LogLevel.INFO, tag, msg, null, payload, null)
    }

    override fun w(tag: String, msg: String, err: Throwable?, payload: Map<String, Any?>) {
        log(LogLevel.WARN, tag, msg, err, payload, null)
    }

    override fun e(tag: String, msg: String, err: Throwable?, payload: Map<String, Any?>) {
        log(LogLevel.ERROR, tag, msg, err, payload, null)
    }

    override fun event(name: String, payload: Map<String, Any?>) {
        log(LogLevel.EVENT, tag = "event", msg = name, throwable = null, payload = payload, eventName = name)
    }

    fun snapshot(): List<LogEvent> = ringBufferRef.get()?.snapshot().orEmpty()

    fun frameTraceMode(): StateFlow<FrameTraceMode> = frameTraceModeState.asStateFlow()

    fun setFrameTraceMode(mode: FrameTraceMode) {
        frameTraceModeState.value = mode
        while (true) {
            val current = configRef.get() ?: break
            val updated = current.copy(frameTraceMode = mode)
            if (configRef.compareAndSet(current, updated)) {
                break
            }
        }
    }

    fun writerStats(): LogWriterStats = writerRef.get()?.stats() ?: LogWriterStats()

    suspend fun flush() {
        writerRef.get()?.flush()
    }

    suspend fun flushAndSync() {
        writerRef.get()?.flushAndSync()
    }

    fun flushAndSyncBlocking() {
        writerRef.get()?.let { runBlocking { it.flushAndSync() } }
    }

    private fun log(
        level: LogLevel,
        tag: String,
        msg: String,
        throwable: Throwable?,
        payload: Map<String, Any?>,
        eventName: String?
    ) {
        val writer = writerRef.get() ?: return
        val deviceInfo = deviceInfoRef.get() ?: return
        val process = processRef.get() ?: ProcessSnapshot(android.os.Process.myPid(), "unknown")
        val thread = Thread.currentThread()
        val normalizedPayload = payload.toMap()
        val threadSnapshot = ThreadSnapshot(
            name = thread.name,
            id = thread.id,
            isMainThread = thread === Looper.getMainLooper()?.thread
        )
        val event = LogEvent(
            level = level,
            tag = tag,
            message = msg,
            eventName = eventName,
            payload = normalizedPayload,
            thread = threadSnapshot,
            process = process,
            device = deviceInfo,
            error = throwable?.let(ThrowableSnapshot::from)
        )
        val config = configRef.get()
        val redacted = config?.redactor?.redact(event) ?: event
        writer.publish(redacted)
    }
}
