package dev.pointtosky.core.logging

import android.util.Log

object LogBridge {
    fun d(tag: String, message: String, payload: Map<String, Any?> = emptyMap()) {
        LogBus.d(tag, message, payload)
        Log.d(tag, message)
    }

    fun i(tag: String, message: String, payload: Map<String, Any?> = emptyMap()) {
        LogBus.i(tag, message, payload)
        Log.i(tag, message)
    }

    fun w(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        LogBus.w(tag, message, throwable, payload)
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }

    fun e(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        payload: Map<String, Any?> = emptyMap(),
    ) {
        LogBus.e(tag, message, throwable, payload)
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}
