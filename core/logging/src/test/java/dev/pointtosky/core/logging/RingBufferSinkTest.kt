package dev.pointtosky.core.logging

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking

class RingBufferSinkTest {
    @Test
    fun `keeps only the latest events`() = runBlocking {
        val sink = RingBufferSink(capacity = 5)
        val device = DeviceInfo(
            manufacturer = "Google",
            model = "Pixel",
            osVersion = "14",
            sdkInt = 34,
            appVersionName = "1.0.0",
            appVersionCode = 1,
            packageName = "dev.pointtosky.test",
            isDebug = true,
            diagnosticsEnabled = true
        )
        val process = ProcessSnapshot(pid = 1, processName = "proc")
        val thread = ThreadSnapshot(name = "main", id = 1L, isMainThread = true)
        repeat(20) { index ->
            val event = LogEvent(
                level = LogLevel.DEBUG,
                tag = "RingBuffer",
                message = "event-$index",
                thread = thread,
                process = process,
                device = device
            )
            sink.write(event)
        }

        val snapshot = sink.snapshot()
        assertEquals(5, snapshot.size)
        assertEquals("event-15", snapshot.first().message)
        assertEquals("event-19", snapshot.last().message)
    }
}
