package dev.pointtosky.core.time

import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface TimeSource {
    fun now(): Instant

    val ticks: Flow<Instant>
}

class SystemTimeSource(
    private val periodMs: Long = 1_000L,
    private val clock: () -> Instant = { Instant.now() },
) : TimeSource {

    init {
        require(periodMs > 0) { "periodMs must be positive" }
    }

    override fun now(): Instant = clock()

    override val ticks: Flow<Instant> = flow {
        emit(now())
        while (true) {
            delay(periodMs)
            emit(now())
        }
    }
}
