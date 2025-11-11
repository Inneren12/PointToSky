package dev.pointtosky.core.logging

import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Clock
import java.time.Instant

/**
 * Represents a single crash event captured by [CrashLogStore].
 */
data class CrashLogEntry(
    val timestamp: Instant,
    val threadName: String,
    val threadId: Long,
    val exceptionType: String,
    val message: String?,
    val stacktrace: String,
    val isFatal: Boolean,
) {
    fun toJsonLine(): String {
        val json = JSONObject()
        json.put(KEY_TIMESTAMP, timestamp.toString())
        json.put(KEY_THREAD_NAME, threadName)
        json.put(KEY_THREAD_ID, threadId)
        json.put(KEY_EXCEPTION_TYPE, exceptionType)
        if (message != null) {
            json.put(KEY_MESSAGE, message)
        } else {
            json.put(KEY_MESSAGE, JSONObject.NULL)
        }
        json.put(KEY_STACKTRACE, stacktrace)
        json.put(KEY_IS_FATAL, isFatal)
        return json.toString()
    }

    companion object {
        private const val KEY_TIMESTAMP = "timestamp"
        private const val KEY_THREAD_NAME = "threadName"
        private const val KEY_THREAD_ID = "threadId"
        private const val KEY_EXCEPTION_TYPE = "exceptionType"
        private const val KEY_MESSAGE = "message"
        private const val KEY_STACKTRACE = "stacktrace"
        private const val KEY_IS_FATAL = "isFatal"

        fun from(thread: Thread, throwable: Throwable, clock: Clock = Clock.systemUTC()): CrashLogEntry {
            val timestamp = Instant.now(clock)
            val stacktrace = buildStackTrace(throwable)
            return CrashLogEntry(
                timestamp = timestamp,
                threadName = thread.name,
                threadId = thread.id,
                exceptionType = throwable::class.java.name,
                message = throwable.message,
                stacktrace = stacktrace,
                isFatal = true,
            )
        }

        fun fromJson(line: String): CrashLogEntry? {
            return try {
                val json = JSONObject(line)
                val timestamp = Instant.parse(json.getString(KEY_TIMESTAMP))
                val threadName = json.optString(KEY_THREAD_NAME, "unknown")
                val threadId = json.optLong(KEY_THREAD_ID, -1L)
                val exceptionType = json.optString(KEY_EXCEPTION_TYPE, "")
                val message = if (json.isNull(KEY_MESSAGE)) {
                    null
                } else {
                    json.optString(KEY_MESSAGE)
                }
                val stacktrace = json.optString(KEY_STACKTRACE, "")
                val isFatal = json.optBoolean(KEY_IS_FATAL, true)
                CrashLogEntry(
                    timestamp = timestamp,
                    threadName = threadName,
                    threadId = threadId,
                    exceptionType = exceptionType,
                    message = message,
                    stacktrace = stacktrace,
                    isFatal = isFatal,
                )
            } catch (error: Exception) {
                null
            }
        }

        private fun buildStackTrace(throwable: Throwable): String {
            val writer = StringWriter()
            PrintWriter(writer).use { printWriter ->
                throwable.printStackTrace(printWriter)
            }
            return writer.toString()
        }
    }
}
