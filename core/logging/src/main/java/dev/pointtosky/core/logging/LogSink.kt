package dev.pointtosky.core.logging

interface LogSink {
    suspend fun write(event: LogEvent)
}

interface FlushableLogSink : LogSink {
    suspend fun flush()
}

interface SyncableLogSink : LogSink {
    suspend fun sync()
}

class MultiSink(private val sinks: List<LogSink>) : LogSink, FlushableLogSink, SyncableLogSink {
    override suspend fun write(event: LogEvent) {
        sinks.forEach { sink ->
            runCatching { sink.write(event) }
        }
    }

    override suspend fun flush() {
        sinks.forEach { sink ->
            if (sink is FlushableLogSink) {
                runCatching { sink.flush() }
            }
        }
    }

    override suspend fun sync() {
        sinks.forEach { sink ->
            when (sink) {
                is SyncableLogSink -> runCatching { sink.sync() }
                is FlushableLogSink -> runCatching { sink.flush() }
            }
        }
    }
}
