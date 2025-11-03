package dev.pointtosky.core.logging

import android.util.Log

object LogBridge {
    fun d(tag: String, message: String) {
        LogBus.d(tag, message)
        Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        LogBus.i(tag, message)
        Log.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        LogBus.w(tag, message, throwable)
        if (throwable != null) {
            Log.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        LogBus.e(tag, message, throwable)
        if (throwable != null) {
            Log.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
        }
    }
}
