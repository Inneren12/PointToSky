package dev.pointtosky.core.logging

import android.util.Log

object LogBridge {
    fun d(tag: String, message: String, throwable: Throwable? = null) {
        LogBus.d(tag, message)
        if (throwable != null) {
            Log.d(tag, message, throwable)
        } else {
            Log.d(tag, message)
        }
    }

    fun i(tag: String, message: String, throwable: Throwable? = null) {
        LogBus.i(tag, message)
        if (throwable != null) {
            Log.i(tag, message, throwable)
        } else {
            Log.i(tag, message)
        }
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
