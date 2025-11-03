package dev.pointtosky.core.logging

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Default coroutine exception handler that forwards uncaught errors to the structured logger.
 */
class LoggingCoroutineExceptionHandler : CoroutineExceptionHandler {
    override val key: CoroutineContext.Key<*> = CoroutineExceptionHandler

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        LogBus.e("CRASH", "uncaught coroutine", exception)
        CrashSafeFlush.flushAndSync()
    }
}
