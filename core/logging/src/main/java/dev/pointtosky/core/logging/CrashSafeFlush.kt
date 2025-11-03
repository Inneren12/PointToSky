package dev.pointtosky.core.logging

object CrashSafeFlush {
    fun flushAndSync() {
        LogBus.flushAndSyncBlocking()
    }

    fun onCrash() {
        flushAndSync()
    }
}
