package dev.pointtosky.core.logging

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RedactorTest {

    private val redactor = Redactor.Privacy
    private val device = DeviceInfo(
        manufacturer = "Google",
        model = "Pixel",
        osVersion = "14",
        sdkInt = 34,
        appVersionName = "1.0.0",
        appVersionCode = 1,
        packageName = "dev.pointtosky.test",
        isDebug = true,
        diagnosticsEnabled = true,
    )
    private val thread = ThreadSnapshot(name = "TestThread", id = 1L, isMainThread = false)
    private val process = ProcessSnapshot(pid = 42, processName = "test")

    @Test
    fun `coordinate redaction - JSON format in message`() {
        val event = LogEvent(
            level = LogLevel.INFO,
            tag = "LocationTest",
            message = "User location: lat=53.123456, lon=-113.987654",
            thread = thread,
            process = process,
            device = device,
        )

        val redacted = redactor.redact(event)

        // Original precise coordinates should not appear in redacted message
        assertFalse(redacted.message.contains("53.123456"), "Precise latitude should be redacted")
        assertFalse(redacted.message.contains("-113.987654"), "Precise longitude should be redacted")
        assertTrue(redacted.message.contains("<redacted>"), "Redacted placeholder should be present")
    }

    @Test
    fun `coordinate redaction - various key formats in message`() {
        val testCases = listOf(
            "latitude=45.123456 longitude=-75.654321" to "latitude=<redacted> longitude=<redacted>",
            "lat: 37.7749, lon: -122.4194" to "lat:<redacted>, lon:<redacted>",
            "LAT=12.345678 LON=98.765432" to "LAT=<redacted> LON=<redacted>",
        )

        testCases.forEach { (input, expected) ->
            val event = LogEvent(
                level = LogLevel.DEBUG,
                tag = "CoordTest",
                message = input,
                thread = thread,
                process = process,
                device = device,
            )

            val redacted = redactor.redact(event)

            assertTrue(redacted.message.contains("<redacted>"),
                "Message should contain redacted placeholder for: $input")
            // Verify original precise numbers are gone
            assertFalse(redacted.message.contains(Regex("""\d+\.\d{4,}""")),
                "Precise coordinates should not appear in: ${redacted.message}")
        }
    }

    @Test
    fun `coordinate redaction - payload with lat lon keys`() {
        val event = LogEvent(
            level = LogLevel.INFO,
            tag = "MapTest",
            message = "Location updated",
            payload = mapOf(
                "lat" to 53.123456,
                "lon" to -113.987654,
                "accuracy" to 15.5,
            ),
            thread = thread,
            process = process,
            device = device,
        )

        val redacted = redactor.redact(event)

        // Coordinates should be rounded to 2 decimal places
        assertEquals(53.12, redacted.payload["lat"], "Latitude should be rounded to 2 decimals")
        assertEquals(-113.99, redacted.payload["lon"], "Longitude should be rounded to 2 decimals")
        // Non-coordinate values should pass through
        assertEquals(15.5, redacted.payload["accuracy"], "Non-coordinate values should not be modified")
    }

    @Test
    fun `coordinate redaction - nested payload with various coordinate key names`() {
        val event = LogEvent(
            level = LogLevel.INFO,
            tag = "GeoTest",
            message = "Test",
            payload = mapOf(
                "location" to mapOf(
                    "latitude" to 45.5017,
                    "longitude" to -73.5673,
                ),
                "destination" to mapOf(
                    "lat" to 37.7749,
                    "lng" to -122.4194,
                ),
            ),
            thread = thread,
            process = process,
            device = device,
        )

        val redacted = redactor.redact(event)

        @Suppress("UNCHECKED_CAST")
        val location = redacted.payload["location"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val destination = redacted.payload["destination"] as? Map<String, Any?>

        // All coordinate keys should be rounded
        assertEquals(45.5, location?.get("latitude"), "Nested latitude should be rounded")
        assertEquals(-73.57, location?.get("longitude"), "Nested longitude should be rounded")
        assertEquals(37.77, destination?.get("lat"), "Nested lat should be rounded")
        assertEquals(-122.42, destination?.get("lng"), "Nested lng should be rounded")
    }

    @Test
    fun `timestamp redaction - rounds to hour`() {
        // Timestamp: 2025-12-02T03:04:05.678Z (1733108645678ms)
        val timestamp = 1733108645678L

        val event = LogEvent(
            timestamp = timestamp,
            level = LogLevel.INFO,
            tag = "TimeTest",
            message = "Event occurred",
            thread = thread,
            process = process,
            device = device,
        )

        val redacted = redactor.redact(event)

        // Should be rounded to: 2025-12-02T03:00:00.000Z (1733108400000ms)
        val expectedRounded = 1733108400000L
        assertEquals(expectedRounded, redacted.timestamp,
            "Timestamp should be rounded to the nearest hour")

        // Verify minutes/seconds/millis are zeroed
        assertEquals(0L, redacted.timestamp % 3600_000L,
            "Minutes, seconds, and milliseconds should be zero")
    }

    @Test
    fun `timestamp redaction - multiple timestamps`() {
        val testCases = listOf(
            // (input_ms, expected_ms, description)
            Triple(1733108645678L, 1733108400000L, "Mid-hour timestamp"),
            Triple(1733110800000L, 1733110800000L, "Exactly on the hour"),
            Triple(1733112399999L, 1733110800000L, "One millisecond before next hour"),
        )

        testCases.forEach { (inputTs, expectedTs, description) ->
            val event = LogEvent(
                timestamp = inputTs,
                level = LogLevel.DEBUG,
                tag = "TimeTest",
                message = "Test: $description",
                thread = thread,
                process = process,
                device = device,
            )

            val redacted = redactor.redact(event)
            assertEquals(expectedTs, redacted.timestamp,
                "Failed for: $description (input=$inputTs, expected=$expectedTs, got=${redacted.timestamp})")
        }
    }

    @Test
    fun `device and account ID redaction - email addresses`() {
        val event = LogEvent(
            level = LogLevel.INFO,
            tag = "AuthTest",
            message = "User logged in: user@example.com, admin.user@test.co.uk",
            thread = thread,
            process = process,
            device = device,
        )

        val redacted = redactor.redact(event)

        assertFalse(redacted.message.contains("user@example.com"),
            "Email should be redacted")
        assertFalse(redacted.message.contains("admin.user@test.co.uk"),
            "Email with subdomain should be redacted")
        assertTrue(redacted.message.contains("<email-redacted>"),
            "Email redaction placeholder should be present")
    }

    @Test
    fun `device and account ID redaction - UUIDs`() {
        val event = LogEvent(
            level = LogLevel.DEBUG,
            tag = "DeviceTest",
            message = "Device ID: 550e8400-e29b-41d4-a716-446655440000",
            thread = thread,
            process = process,
            device = device,
        )

        val redacted = redactor.redact(event)

        assertFalse(redacted.message.contains("550e8400-e29b-41d4-a716-446655440000"),
            "UUID should be redacted")
        assertTrue(redacted.message.contains("<uuid-redacted>"),
            "UUID redaction placeholder should be present")
    }

    @Test
    fun `device and account ID redaction - hex device IDs`() {
        val event = LogEvent(
            level = LogLevel.INFO,
            tag = "HardwareTest",
            message = "Hardware ID: 0123456789abcdef0123456789abcdef",
            thread = thread,
            process = process,
            device = device,
        )

        val redacted = redactor.redact(event)

        assertFalse(redacted.message.contains("0123456789abcdef0123456789abcdef"),
            "Long hex ID should be redacted")
        assertTrue(redacted.message.contains("<device-id-redacted>"),
            "Device ID redaction placeholder should be present")
    }

    @Test
    fun `device and account ID redaction - in payload strings`() {
        val event = LogEvent(
            level = LogLevel.INFO,
            tag = "PayloadTest",
            message = "Data collected",
            payload = mapOf(
                "userId" to "user@example.com",
                "deviceId" to "abcdef1234567890abcdef1234567890",
                "sessionId" to "550e8400-e29b-41d4-a716-446655440000",
                "normalValue" to "just a string",
            ),
            thread = thread,
            process = process,
            device = device,
        )

        val redacted = redactor.redact(event)

        val userId = redacted.payload["userId"] as? String
        val deviceId = redacted.payload["deviceId"] as? String
        val sessionId = redacted.payload["sessionId"] as? String
        val normalValue = redacted.payload["normalValue"] as? String

        assertEquals("<email-redacted>", userId, "Email in payload should be redacted")
        assertTrue(deviceId?.contains("<device-id-redacted>") == true,
            "Device ID in payload should be redacted")
        assertEquals("<uuid-redacted>", sessionId, "UUID in payload should be redacted")
        assertEquals("just a string", normalValue,
            "Normal strings should not be affected")
    }

    @Test
    fun `error redaction - message and stacktrace`() {
        val throwable = ThrowableSnapshot(
            type = "IllegalStateException",
            message = "Failed for user@example.com with device 0123456789abcdef0123456789abcdef",
            stackTrace = """
                java.lang.IllegalStateException: Error occurred
                    at com.example.MyClass.doSomething(MyClass.kt:42)
                    at /home/user123/project/src/Main.kt:100
                    at file:///Users/john.doe/app/MyApp.java:55
            """.trimIndent()
        )

        val event = LogEvent(
            level = LogLevel.ERROR,
            tag = "ErrorTest",
            message = "Exception occurred",
            thread = thread,
            process = process,
            device = device,
            error = throwable,
        )

        val redacted = redactor.redact(event)

        // Error message should have IDs redacted
        assertFalse(redacted.error?.message?.contains("user@example.com") == true,
            "Email in error message should be redacted")
        assertTrue(redacted.error?.message?.contains("<email-redacted>") == true,
            "Email redaction placeholder should be present in error message")

        // Stack trace should have file paths sanitized
        val stackTrace = redacted.error?.stackTrace ?: ""
        assertFalse(stackTrace.contains("/home/user123"),
            "User home directory should be redacted from stack trace")
        assertFalse(stackTrace.contains("/Users/john.doe"),
            "User path should be redacted from stack trace")

        // Should keep just filenames
        assertTrue(stackTrace.contains("MyClass.kt") || stackTrace.contains("Main.kt"),
            "Filenames should be preserved")
    }

    @Test
    fun `passthrough redactor does not modify event`() {
        val original = LogEvent(
            timestamp = 1733108645678L,
            level = LogLevel.INFO,
            tag = "Test",
            message = "lat=53.123456, user@example.com",
            payload = mapOf(
                "lat" to 53.123456,
                "email" to "test@example.com"
            ),
            thread = thread,
            process = process,
            device = device,
        )

        val redacted = Redactor.Passthrough.redact(original)

        // Should be identical
        assertEquals(original, redacted, "Passthrough redactor should not modify the event")
    }

    @Test
    fun `crash log entry redaction`() {
        val entry = CrashLogEntry(
            timestamp = Instant.ofEpochMilli(1733108645678L), // 2025-12-02T03:04:05.678Z
            threadName = "main",
            threadId = 1L,
            exceptionType = "IllegalStateException",
            message = "Failed for user@example.com at lat=53.123456",
            stacktrace = """
                java.lang.IllegalStateException: Error
                    at /home/username/app/Main.kt:100
            """.trimIndent(),
            isFatal = true
        )

        val redacted = Redactor.Privacy.redact(entry)

        // Timestamp should be rounded to hour
        assertEquals(0, redacted.timestamp.nano, "Nanoseconds should be zero")
        assertEquals(0L, redacted.timestamp.epochSecond % 3600, "Should be rounded to hour")

        // Message should be redacted
        assertFalse(redacted.message?.contains("user@example.com") == true,
            "Email should be redacted from crash log message")
        assertFalse(redacted.message?.contains("53.123456") == true,
            "Coordinates should be redacted from crash log message")

        // Stack trace should be sanitized
        assertFalse(redacted.stacktrace.contains("/home/username"),
            "User paths should be redacted from crash log stacktrace")
    }

    @Test
    fun `redactor handles edge cases gracefully`() {
        // Empty payload, no error
        val minimal = LogEvent(
            level = LogLevel.INFO,
            tag = "Test",
            message = "",
            thread = thread,
            process = process,
            device = device,
        )

        val redacted = redactor.redact(minimal)
        assertEquals("", redacted.message)
        assertTrue(redacted.payload.isEmpty())

        // Null values in payload
        val withNulls = LogEvent(
            level = LogLevel.INFO,
            tag = "Test",
            message = "test",
            payload = mapOf("key" to null, "lat" to null),
            thread = thread,
            process = process,
            device = device,
        )

        val redactedNulls = redactor.redact(withNulls)
        assertEquals(null, redactedNulls.payload["key"])
        assertEquals(null, redactedNulls.payload["lat"])
    }

    @Test
    fun `path redaction - Windows paths with spaces`() {
        val throwable = ThrowableSnapshot(
            type = "IllegalStateException",
            message = "Error occurred",
            stackTrace = """
                java.lang.IllegalStateException: Error occurred
                    at com.example.MyClass.doSomething(MyClass.kt:42)
                    at C:\Users\John Doe\App\Main.kt:10
                    at C:\Program Files\MyApp\src\Utils.kt:25
            """.trimIndent()
        )

        val event = LogEvent(
            level = LogLevel.ERROR,
            tag = "PathTest",
            message = "Exception occurred",
            thread = thread,
            process = process,
            device = device,
            error = throwable,
        )

        val redacted = redactor.redact(event)
        val stackTrace = redacted.error?.stackTrace ?: ""

        // Original paths with usernames/spaces should be removed
        assertFalse(stackTrace.contains("John Doe"),
            "Username should be redacted from Windows path")
        assertFalse(stackTrace.contains("C:\\Users\\John Doe"),
            "Full Windows path with spaces should be redacted")
        assertFalse(stackTrace.contains("Program Files"),
            "Program Files directory should be redacted")

        // Filenames with line numbers should be preserved
        assertTrue(stackTrace.contains("Main.kt:10"),
            "Filename with line number should be preserved")
        assertTrue(stackTrace.contains("Utils.kt:25"),
            "Filename with line number should be preserved")
    }

    @Test
    fun `path redaction - Unix paths with spaces`() {
        val throwable = ThrowableSnapshot(
            type = "NullPointerException",
            message = "Null value",
            stackTrace = """
                java.lang.NullPointerException: Null value
                    at /Users/John Doe/app/Main.kt:100
                    at /home/user name/project/src/Handler.java:55
                    at /Applications/My App/lib/Core.kt:200
            """.trimIndent()
        )

        val event = LogEvent(
            level = LogLevel.ERROR,
            tag = "PathTest",
            message = "Exception occurred",
            thread = thread,
            process = process,
            device = device,
            error = throwable,
        )

        val redacted = redactor.redact(event)
        val stackTrace = redacted.error?.stackTrace ?: ""

        // Original paths with usernames/spaces should be removed
        assertFalse(stackTrace.contains("John Doe"),
            "Username should be redacted from Unix path")
        assertFalse(stackTrace.contains("/Users/John Doe"),
            "Full Unix path with spaces should be redacted")
        assertFalse(stackTrace.contains("user name"),
            "Username with space should be redacted")
        assertFalse(stackTrace.contains("/Applications/My App"),
            "Application path with spaces should be redacted")

        // Filenames with line numbers should be preserved
        assertTrue(stackTrace.contains("Main.kt:100"),
            "Filename with line number should be preserved")
        assertTrue(stackTrace.contains("Handler.java:55"),
            "Filename with line number should be preserved")
        assertTrue(stackTrace.contains("Core.kt:200"),
            "Filename with line number should be preserved")
    }

    @Test
    fun `path redaction - file protocol URLs with spaces`() {
        val throwable = ThrowableSnapshot(
            type = "RuntimeException",
            message = "Runtime error",
            stackTrace = """
                java.lang.RuntimeException: Runtime error
                    at file:///Users/John Doe/app/MyApp.java:55
                    at file://C:\Users\Jane Smith\App\Main.kt:10
                    at file:///home/user 123/src/Core.kt:42
            """.trimIndent()
        )

        val event = LogEvent(
            level = LogLevel.ERROR,
            tag = "PathTest",
            message = "Exception occurred",
            thread = thread,
            process = process,
            device = device,
            error = throwable,
        )

        val redacted = redactor.redact(event)
        val stackTrace = redacted.error?.stackTrace ?: ""

        // Original paths with usernames/spaces should be removed
        assertFalse(stackTrace.contains("John Doe"),
            "Username should be redacted from file:// URL")
        assertFalse(stackTrace.contains("Jane Smith"),
            "Username should be redacted from file:// URL")
        assertFalse(stackTrace.contains("user 123"),
            "Username should be redacted from file:// URL")
        assertFalse(stackTrace.contains("file:///Users/John Doe"),
            "Full file:// path should be redacted")

        // Filenames with line numbers should be preserved
        assertTrue(stackTrace.contains("MyApp.java:55"),
            "Filename with line number should be preserved")
        assertTrue(stackTrace.contains("Main.kt:10"),
            "Filename with line number should be preserved")
        assertTrue(stackTrace.contains("Core.kt:42"),
            "Filename with line number should be preserved")
    }

    @Test
    fun `path redaction - mixed paths with and without spaces`() {
        val throwable = ThrowableSnapshot(
            type = "IllegalArgumentException",
            message = "Invalid argument",
            stackTrace = """
                java.lang.IllegalArgumentException: Invalid argument
                    at /home/user/project/Main.kt:10
                    at /home/john doe/app/Handler.kt:20
                    at C:\Users\admin\App\Core.kt:30
                    at C:\Program Files\MyApp\Utils.kt:40
            """.trimIndent()
        )

        val event = LogEvent(
            level = LogLevel.ERROR,
            tag = "PathTest",
            message = "Exception occurred",
            thread = thread,
            process = process,
            device = device,
            error = throwable,
        )

        val redacted = redactor.redact(event)
        val stackTrace = redacted.error?.stackTrace ?: ""

        // All paths should be redacted, whether they have spaces or not
        assertFalse(stackTrace.contains("/home/user/project"),
            "Path without spaces should be redacted")
        assertFalse(stackTrace.contains("john doe"),
            "Path with spaces should be redacted")
        assertFalse(stackTrace.contains("C:\\Users\\admin"),
            "Windows path without spaces should be redacted")
        assertFalse(stackTrace.contains("Program Files"),
            "Windows path with spaces should be redacted")

        // All filenames should be preserved
        assertTrue(stackTrace.contains("Main.kt:10"),
            "Filename should be preserved")
        assertTrue(stackTrace.contains("Handler.kt:20"),
            "Filename should be preserved")
        assertTrue(stackTrace.contains("Core.kt:30"),
            "Filename should be preserved")
        assertTrue(stackTrace.contains("Utils.kt:40"),
            "Filename should be preserved")
    }
}
