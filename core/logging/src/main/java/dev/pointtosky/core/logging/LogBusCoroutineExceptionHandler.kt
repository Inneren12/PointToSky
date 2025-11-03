package dev.pointtosky.core.logging

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class LogBusCoroutineExceptionHandler : AbstractCoroutineContextElement(CoroutineExceptionHandler),
    CoroutineExceptionHandler {

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        LogBus.e(
            tag = "CRASH",
            msg = "Unhandled coroutine exception",
            err = exception,
            payload = mapOf("context" to context.toString())
        )
        CrashSafeFlush.flushAndSync()
    }
}
