package dev.pointtosky.core.logging

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler

class LogBusCoroutineExceptionHandler :
    AbstractCoroutineContextElement(CoroutineExceptionHandler),
    CoroutineExceptionHandler {

    override fun handleException(context: CoroutineContext, exception: Throwable) {
        LogBus.e(tag = "CRASH", msg = "Coroutine uncaught", err = exception)
        CrashSafeFlush.flushAndSync()
    }
}
