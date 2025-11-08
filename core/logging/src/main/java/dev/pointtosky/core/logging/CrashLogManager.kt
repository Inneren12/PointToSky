package dev.pointtosky.core.logging

import android.content.Context
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.StateFlow

/**
 * Provides access to crash logging facilities across the app.
 */
object CrashLogManager {
    private val storeRef = AtomicReference<CrashLogStore?>()

    fun initialize(context: Context) {
        if (storeRef.get() != null) return
        val appContext = context.applicationContext
        val directory = File(appContext.filesDir, "crash")
        val store = CrashLogStore(directory)
        if (storeRef.compareAndSet(null, store)) {
            installDefaultHandlerInternal(store)
        }
    }

    fun installDefaultHandler() {
        val store = storeRef.get() ?: return
        installDefaultHandlerInternal(store)
    }

    fun lastCrashFlow(): StateFlow<CrashLogEntry?> = requireStore().lastCrash()

    fun currentLastCrash(): CrashLogEntry? = requireStore().currentLastCrash()

    fun clear() {
        requireStore().clear()
    }

    fun createZip(targetDirectory: File): File? = requireStore().createZip(targetDirectory)

    fun hasLogs(): Boolean = requireStore().hasLogs()

    private fun installDefaultHandlerInternal(store: CrashLogStore) {
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current is CrashUncaughtExceptionHandler && current.store === store) {
            return
        }
        val handler = CrashUncaughtExceptionHandler(store, current)
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    private fun requireStore(): CrashLogStore {
        return storeRef.get() ?: throw IllegalStateException("CrashLogManager is not initialized")
    }
}
