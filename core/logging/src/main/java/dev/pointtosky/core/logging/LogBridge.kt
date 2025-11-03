package dev.pointtosky.core.logging

import android.util.Log

/**
 * Bridges android.util.Log calls to the structured logger while still emitting to Logcat.
 */
object LogBridge {
    fun d(tag: String, message: String, payload: Map<String, Any?> = emptyMap()) {
        LogBus.d(tag, message, payload)
        Log.d(tag, formatMessage(message, payload))
    }

    fun i(tag: String, message: String, payload: Map<String, Any?> = emptyMap()) {
        LogBus.i(tag, message, payload)
        Log.i(tag, formatMessage(message, payload))
    }

    fun w(tag: String, message: String, throwable: Throwable? = null, payload: Map<String, Any?> = emptyMap()) {
        LogBus.w(tag, message, throwable, payload)
        if (throwable != null) {
            Log.w(tag, formatMessage(message, payload), throwable)
        } else {
            Log.w(tag, formatMessage(message, payload))
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null, payload: Map<String, Any?> = emptyMap()) {
        LogBus.e(tag, message, throwable, payload)
        if (throwable != null) {
            Log.e(tag, formatMessage(message, payload), throwable)
        } else {
            Log.e(tag, formatMessage(message, payload))
        }
    }

    private fun formatMessage(message: String, payload: Map<String, Any?>): String {
        return if (payload.isEmpty()) message else "$message | payload=${payload.toSortedMap()}"
    }
}
