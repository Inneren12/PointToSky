package dev.pointtosky.core.logging

import kotlin.math.round

/**
 * Hook for sanitising Personally Identifiable Information (PII) before the event is persisted.
 * Currently a passthrough implementation is used. Integrators can swap the implementation at
 * runtime via [LoggerConfig].
 */
fun interface Redactor {
    fun redact(event: LogEvent): LogEvent

    object Passthrough : Redactor {
        override fun redact(event: LogEvent): LogEvent = event
    }

    /**
     * Privacy-safe redactor that sanitizes logs before export.
     *
     * Redaction behavior:
     * - **Coordinates**: Reduces lat/lon precision to 2 decimal places (~1.1km precision at equator)
     * - **Timestamps**: Rounds to the nearest hour (zeroes out minutes/seconds/millis)
     * - **Device/Account IDs**: Replaces patterns like device IDs, emails, UUIDs with placeholders
     * - **Stack traces**: Sanitizes file paths that might contain usernames
     *
     * Fields affected:
     * - `timestamp`: Rounded to hour
     * - `message`: Redacted for IDs and coordinates
     * - `payload`: Recursively redacted (coordinates, IDs)
     * - `error.message`: Redacted for IDs
     * - `error.stackTrace`: Redacted for sensitive paths
     */
    object Privacy : Redactor {
        /**
         * Redacts a crash log entry for safe export.
         * Similar to LogEvent redaction but operates on CrashLogEntry structure.
         */
        fun redact(entry: CrashLogEntry): CrashLogEntry {
            return entry.copy(
                timestamp = entry.timestamp.minusNanos(
                    entry.timestamp.nano.toLong()
                ).minusSeconds(
                    entry.timestamp.epochSecond % 3600
                ),
                message = entry.message?.let { redactString(it) },
                stacktrace = redactStackTrace(entry.stacktrace)
            )
        }
        override fun redact(event: LogEvent): LogEvent {
            return event.copy(
                timestamp = roundTimestampToHour(event.timestamp),
                message = redactString(event.message),
                payload = redactPayload(event.payload),
                error = event.error?.let { redactThrowable(it) }
            )
        }

        /**
         * Rounds timestamp to the nearest hour (zeroes minutes, seconds, milliseconds).
         */
        private fun roundTimestampToHour(timestampMillis: Long): Long {
            val millisInHour = 3600_000L
            return (timestampMillis / millisInHour) * millisInHour
        }

        /**
         * Redacts sensitive patterns in strings:
         * - Coordinates (lat/lon)
         * - Email addresses
         * - UUIDs
         * - Android device IDs (hex patterns)
         */
        private fun redactString(input: String): String {
            var result = input

            // Redact coordinates in various formats
            // Pattern: lat/latitude/lon/longitude followed by = or : and a number
            result = result.replace(Regex("""(?i)(lat(?:itude)?)\s*[=:]\s*-?\d+\.\d+""")) {
                "${it.groupValues[1]}=<redacted>"
            }
            result = result.replace(Regex("""(?i)(lon(?:gitude)?)\s*[=:]\s*-?\d+\.\d+""")) {
                "${it.groupValues[1]}=<redacted>"
            }

            // Redact email addresses
            result = result.replace(Regex("""[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}""")) {
                "<email-redacted>"
            }

            // Redact UUIDs (8-4-4-4-12 hex pattern)
            result = result.replace(Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")) {
                "<uuid-redacted>"
            }

            // Redact potential device IDs (16+ hex characters)
            result = result.replace(Regex("""(?i)\b[0-9a-fA-F]{16,}\b""")) {
                "<device-id-redacted>"
            }

            return result
        }

        /**
         * Recursively redacts payload map:
         * - Coordinates: reduces precision to 2 decimal places
         * - Strings: applies string redaction
         * - Nested maps/lists: recursively processes
         */
        private fun redactPayload(payload: Map<String, Any?>): Map<String, Any?> {
            return payload.mapValues { (key, value) ->
                redactValue(key, value)
            }
        }

        private fun redactValue(key: String?, value: Any?): Any? {
            return when {
                value == null -> null

                // Redact coordinate values (lat/lon keys with numeric values)
                key != null && isCoordinateKey(key) && value is Number -> {
                    roundCoordinate(value.toDouble())
                }

                // Recursively redact nested maps
                value is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    redactPayload(value as Map<String, Any?>)
                }

                // Recursively redact lists
                value is List<*> -> {
                    value.map { redactValue(null, it) }
                }

                // Redact strings
                value is String -> redactString(value)

                // Pass through other types (numbers, booleans, enums, etc.)
                else -> value
            }
        }

        /**
         * Checks if a key name represents a coordinate field.
         */
        private fun isCoordinateKey(key: String): Boolean {
            val normalized = key.lowercase()
            return normalized in setOf("lat", "latitude", "lon", "lng", "longitude")
        }

        /**
         * Rounds coordinate to 2 decimal places (~1.1km precision at equator).
         */
        private fun roundCoordinate(value: Double): Double {
            return round(value * 100.0) / 100.0
        }

        /**
         * Redacts throwable snapshot:
         * - message: applies string redaction
         * - stackTrace: sanitizes file paths
         */
        private fun redactThrowable(throwable: ThrowableSnapshot): ThrowableSnapshot {
            return throwable.copy(
                message = throwable.message?.let { redactString(it) },
                stackTrace = redactStackTrace(throwable.stackTrace)
            )
        }

        /**
         * Redacts sensitive information from stack traces:
         * - Replaces full file paths with just filenames (removes potential usernames in paths)
         * - Redacts potential IDs in stack traces
         */
        private fun redactStackTrace(stackTrace: String): String {
            var result = stackTrace

            // Redact full file paths (e.g., /Users/John Doe/... or C:\Users\John Doe\...)
            // Handles paths with spaces, keeps just the filename (and optional :line)
            // Pattern matches:
            //   - Optional file:// prefix
            //   - Windows drive (C:\) or Unix root (/)
            //   - Any characters until the last path separator (including spaces)
            //   - Captures: filename.ext or filename.ext:line
            result = result.replace(Regex("""(?:file://)?(?:[A-Za-z]:[\\/]|/)[\S ]*?[\\/](\S+?\.\w+(?::\d+)?)""")) {
                it.groupValues[1] // Keep only filename[:line]
            }

            // Apply general string redaction (IDs, emails, etc.)
            result = redactString(result)

            return result
        }
    }
}
