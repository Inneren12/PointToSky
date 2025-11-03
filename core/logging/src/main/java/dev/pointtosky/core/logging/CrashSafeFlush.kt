package dev.pointtosky.core.logging

object CrashSafeFlush {
    fun onCrash() {
        LogBus.flushAndSyncBlocking()
    }
}
