package dev.pointtosky.core.logging

import android.os.Process
import kotlin.system.exitProcess

internal class CrashUncaughtExceptionHandler(
    val store: CrashLogStore,
    private val delegate: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            store.record(thread, throwable)
        } catch (_: Throwable) {
            // Swallow errors – crash logging must never crash the app.
        } finally {
            try {
                CrashSafeFlush.flushAndSyncBlocking()
            } catch (_: Throwable) {
                // Ignore flush failures – process is crashing anyway.
            }
        }
        delegate?.uncaughtException(thread, throwable) ?: run {
            Process.killProcess(Process.myPid())
            exitProcess(10)
        }
    }
}
