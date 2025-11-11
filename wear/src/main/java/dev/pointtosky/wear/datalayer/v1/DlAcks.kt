package dev.pointtosky.wear.datalayer.v1

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal object DlAcks {
    private val waits = ConcurrentHashMap<String, CountDownLatch>()

    fun register(cid: String): CountDownLatch {
        val latch = CountDownLatch(1)
        waits[cid] = latch
        return latch
    }

    fun complete(refCid: String) {
        waits.remove(refCid)?.countDown()
    }

    fun await(latch: CountDownLatch, timeoutMs: Long): Boolean = latch.await(timeoutMs, TimeUnit.MILLISECONDS)
}
