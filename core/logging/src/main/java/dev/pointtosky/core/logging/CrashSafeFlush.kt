package dev.pointtosky.core.logging

object CrashSafeFlush {
    fun onCrash() {
        flushAndSync()
    }

    fun flushAndSync() {
        LogBus.flushAndSyncBlocking()
    }
}
