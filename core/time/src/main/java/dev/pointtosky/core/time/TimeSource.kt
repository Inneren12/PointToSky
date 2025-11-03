package dev.pointtosky.core.time

import java.time.Instant
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.ticker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

interface TimeSource {
    fun now(): Instant

    val ticks: Flow<Instant>
}

class SystemTimeSource(
    periodMs: Long = DEFAULT_PERIOD_MS,
    private val clock: () -> Instant = { Instant.now() },
) : TimeSource {

    init {
        require(periodMs > 0) { "periodMs must be positive" }
    }

    private val _periodMs = MutableStateFlow(periodMs)

    val periodMsFlow: StateFlow<Long> = _periodMs.asStateFlow()

    fun updatePeriod(periodMs: Long) {
        require(periodMs > 0) { "periodMs must be positive" }
        _periodMs.value = periodMs
    }

    override fun now(): Instant = clock()

    @OptIn(ObsoleteCoroutinesApi::class)
    override val ticks: Flow<Instant> = _periodMs
        .distinctUntilChanged()
        .flatMapLatest { period ->
            callbackFlow {
                trySend(now())
                val ticker = ticker(delayMillis = period, initialDelayMillis = period)
                val job = launch {
                    for (ignored in ticker) {
                        trySend(now())
                    }
                }
                awaitClose {
                    ticker.cancel()
                    job.cancel()
                }
            }
        }

    companion object {
        private const val DEFAULT_PERIOD_MS = 1_000L
    }
}
