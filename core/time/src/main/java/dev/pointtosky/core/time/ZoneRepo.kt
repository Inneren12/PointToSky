package dev.pointtosky.core.time

import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object ZoneRepo {
    private val zoneState = MutableStateFlow(ZoneId.systemDefault())

    val zoneFlow: Flow<ZoneId> = zoneState.asStateFlow()

    fun current(): ZoneId = zoneState.value

    fun set(zoneId: ZoneId) {
        zoneState.value = zoneId
    }

    fun refresh() {
        zoneState.value = ZoneId.systemDefault()
    }
}
