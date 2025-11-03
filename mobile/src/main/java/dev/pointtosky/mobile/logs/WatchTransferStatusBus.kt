package dev.pointtosky.mobile.logs

import dev.pointtosky.core.common.logs.WatchTransferEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

object WatchTransferStatusBus {
    private val _events = MutableSharedFlow<WatchTransferEvent>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<WatchTransferEvent> = _events

    fun emit(event: WatchTransferEvent) {
        _events.tryEmit(event)
    }
}
