package dev.pointtosky.core.logging

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class LogEventJsonTest {
    @Test
    fun `serializes payload and metadata`() {
        val device = DeviceInfo(
            manufacturer = "Google",
            model = "Pixel",
            osVersion = "14",
            sdkInt = 34,
            appVersionName = "1.0.0",
            appVersionCode = 1,
            packageName = "dev.pointtosky.test",
            flavor = "test",
            supportedAbis = listOf("arm64-v8a", "x86_64"),
            sensors = mapOf("accelerometer" to true, "gyroscope" to false),
            isDebug = true,
            diagnosticsEnabled = true
        )
        val thread = ThreadSnapshot(name = "TestThread", id = 1L, isMainThread = false)
        val process = ProcessSnapshot(pid = 42, processName = "test")
        val throwable = ThrowableSnapshot(
            type = IllegalStateException::class.java.name,
            message = "boom",
            stackTrace = "trace"
        )
        val event = LogEvent(
            level = LogLevel.ERROR,
            tag = "LoggerTest",
            message = "something happened",
            eventName = "crash",
            payload = mapOf(
                "id" to 123,
                "nested" to mapOf("value" to true),
                "list" to listOf("a", "b")
            ),
            thread = thread,
            process = process,
            device = device,
            error = throwable
        )

        val json = Json.parseToJsonElement(event.toJsonLine()).jsonObject

        assertEquals("LoggerTest", json["tag"]?.jsonPrimitive?.content)
        assertEquals("ERROR", json["level"]?.jsonPrimitive?.content)
        assertEquals("crash", json["event"]?.jsonPrimitive?.content)
        val payload = json["payload"]?.jsonObject
        assertNotNull(payload)
        assertEquals("123", payload["id"]?.jsonPrimitive?.content)
        assertTrue(payload["nested"]?.jsonObject?.get("value")?.jsonPrimitive?.boolean == true)
        val threadJson = json["thread"]?.jsonObject
        assertEquals("TestThread", threadJson?.get("name")?.jsonPrimitive?.content)
        val deviceJson = json["device"]?.jsonObject
        assertEquals("Pixel", deviceJson?.get("model")?.jsonPrimitive?.content)
        assertEquals(true, deviceJson?.get("diagnosticsEnabled")?.jsonPrimitive?.boolean)
        val errorJson = json["error"]?.jsonObject
        assertEquals("boom", errorJson?.get("message")?.jsonPrimitive?.content)
    }
}
