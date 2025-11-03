package dev.pointtosky.core.logging

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class LogWriter(
    private val sink: LogSink,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val flushIntervalMs: Long = 500L,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val channel = Channel<LogCommand>(capacity = CHANNEL_CAPACITY)
    private val pendingCommands = AtomicInteger(0)
    private val droppedEvents = AtomicLong(0)
    private val processor = scope.launch {
        for (command in channel) {
            pendingCommands.updateAndGet { current -> if (current > 0) current - 1 else 0 }
            when (command) {
                is LogCommand.Event -> sink.write(command.event)
                is LogCommand.Flush -> {
                    val flushable = sink as? FlushableLogSink
                    val syncable = sink as? SyncableLogSink
                    when {
                        flushable != null -> flushable.flush()
                        syncable != null -> syncable.sync()
                    }
                    if (command.sync) {
                        syncable?.sync()
                    }
                    command.completion?.complete(Unit)
                }
            }
        }
    }
    private val tickerJob = if (flushIntervalMs > 0) {
        scope.launch {
            while (isActive) {
                delay(flushIntervalMs)
                enqueue(LogCommand.Flush(null, sync = false), dropIfFull = true)
            }
        }
    } else null

    fun publish(event: LogEvent) {
        val enqueued = enqueue(LogCommand.Event(event), dropIfFull = true)
        if (!enqueued) {
            droppedEvents.incrementAndGet()
        }
    }

    suspend fun flush() {
        val completion = CompletableDeferred<Unit>()
        enqueueBlocking(LogCommand.Flush(completion, sync = false))
        completion.await()
    }

    suspend fun flushAndSync() {
        val completion = CompletableDeferred<Unit>()
        enqueueBlocking(LogCommand.Flush(completion, sync = true))
        completion.await()
    }

    suspend fun shutdown() {
        tickerJob?.cancel()
        channel.close()
        processor.join()
        (sink as? FlushableLogSink)?.flush()
        scope.cancel()
    }

    fun stats(): LogWriterStats = LogWriterStats(
        queuedEvents = pendingCommands.get(),
        droppedEvents = droppedEvents.get(),
    )

    private fun enqueue(command: LogCommand, dropIfFull: Boolean): Boolean {
        val result = channel.trySend(command)
        if (result.isSuccess) {
            pendingCommands.incrementAndGet()
            return true
        }
        if (!dropIfFull) {
            runBlocking {
                channel.send(command)
                pendingCommands.incrementAndGet()
            }
            return true
        }
        return false
    }

    private fun enqueueBlocking(command: LogCommand) {
        if (!enqueue(command, dropIfFull = false)) {
            throw IllegalStateException("Failed to enqueue log command")
        }
    }

    private sealed interface LogCommand {
        data class Event(val event: LogEvent) : LogCommand
        data class Flush(val completion: CompletableDeferred<Unit>?, val sync: Boolean) : LogCommand
    }

    private companion object {
        private const val CHANNEL_CAPACITY = 256
    }
}

data class LogWriterStats(
    val queuedEvents: Int = 0,
    val droppedEvents: Long = 0,
)
