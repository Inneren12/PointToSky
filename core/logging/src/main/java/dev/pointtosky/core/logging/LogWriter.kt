package dev.pointtosky.core.logging

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
    private val flushIntervalMs: Long = 500L
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val channel = Channel<LogCommand>(capacity = CHANNEL_CAPACITY)
    private val processor = scope.launch {
        for (command in channel) {
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
                channel.trySend(LogCommand.Flush(null, sync = false))
            }
        }
    } else null

    fun publish(event: LogEvent) {
        if (!channel.trySend(LogCommand.Event(event)).isSuccess) {
            runBlocking { channel.send(LogCommand.Event(event)) }
        }
    }

    suspend fun flush() {
        val completion = CompletableDeferred<Unit>()
        channel.send(LogCommand.Flush(completion, sync = false))
        completion.await()
    }

    suspend fun flushAndSync() {
        val completion = CompletableDeferred<Unit>()
        channel.send(LogCommand.Flush(completion, sync = true))
        completion.await()
    }

    suspend fun shutdown() {
        tickerJob?.cancel()
        channel.close()
        processor.join()
        (sink as? FlushableLogSink)?.flush()
        scope.cancel()
    }

    private sealed interface LogCommand {
        data class Event(val event: LogEvent) : LogCommand
        data class Flush(val completion: CompletableDeferred<Unit>?, val sync: Boolean) : LogCommand
    }

    private companion object {
        private const val CHANNEL_CAPACITY = 256
    }
}
