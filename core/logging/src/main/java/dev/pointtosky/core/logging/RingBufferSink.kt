package dev.pointtosky.core.logging

import java.util.ArrayDeque
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RingBufferSink(private val capacity: Int) : LogSink {
    private val lock = ReentrantLock()
    private val buffer: ArrayDeque<LogEvent> = ArrayDeque(capacity)

    override suspend fun write(event: LogEvent) {
        lock.withLock {
            if (buffer.size == capacity) {
                buffer.removeFirst()
            }
            buffer.addLast(event)
        }
    }

    fun snapshot(): List<LogEvent> = lock.withLock { buffer.toList() }
}
