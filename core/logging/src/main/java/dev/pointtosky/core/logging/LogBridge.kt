package dev.pointtosky.core.logging

import android.util.Log

object LogBridge {
    fun d(tag: String, message: String) {
        Log.d(tag, message)
        LogBus.d(tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        LogBus.i(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(tag, message, throwable)
            LogBus.w(tag, message, throwable)
        } else {
            Log.w(tag, message)
            LogBus.w(tag, message)
        }
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(tag, message, throwable)
            LogBus.e(tag, message, throwable)
        } else {
            Log.e(tag, message)
            LogBus.e(tag, message)
        }
    }
}
