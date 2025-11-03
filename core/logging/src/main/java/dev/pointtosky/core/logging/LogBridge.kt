package dev.pointtosky.core.logging

import android.util.Log

object LogBridge {
    fun d(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        if (throwable != null) {
            Log.d(tag, message, throwable)
        } else {
            Log.d(tag, message)
        }
        LogBus.d(tag, message, payload.withThrowableDetails(throwable))
    }

    fun i(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        if (throwable != null) {
            Log.i(tag, message, throwable)
        } else {
            Log.i(tag, message)
        }
        LogBus.i(tag, message, payload.withThrowableDetails(throwable))
    }

    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
        LogBus.w(tag, message, throwable, payload)
    }

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
        LogBus.e(tag, message, throwable, payload)
    }

    private fun Map<String, Any?>.withThrowableDetails(throwable: Throwable?): Map<String, Any?> {
        if (throwable == null || this.containsKey("throwable")) {
            return this
        }
        val throwablePayload = mapOf(
            "type" to throwable::class.java.name,
            "message" to throwable.message,
            "stacktrace" to throwable.stackTraceToString(),
        )
        return this + ("throwable" to throwablePayload)
    }
}
