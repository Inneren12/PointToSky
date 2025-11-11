package dev.pointtosky.core.time

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ObsoleteCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.time.Instant

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

    private val periodMsState = MutableStateFlow(periodMs)

    val periodMsFlow: StateFlow<Long> = periodMsState.asStateFlow()
    fun updatePeriod(periodMs: Long) {
        require(periodMs > 0) { "periodMs must be positive" }
        periodMsState.value = periodMs
    }

    override fun now(): Instant = clock()

    @OptIn(ObsoleteCoroutinesApi::class, ExperimentalCoroutinesApi::class)
    override val ticks: Flow<Instant> = periodMsState
        .flatMapLatest { period ->
            flow {
                // сразу отдать первый тик (как у тебя было)
                emit(now())

                // затем тикаем каждые period мс, пока активен scope
                while (currentCoroutineContext().isActive) {
                    delay(period)
                    emit(now())
                }
            }
        }

    companion object {
        private const val DEFAULT_PERIOD_MS = 1_000L
    }
}
