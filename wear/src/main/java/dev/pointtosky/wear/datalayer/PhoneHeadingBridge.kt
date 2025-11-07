package dev.pointtosky.wear.datalayer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.floorMod

/**
 * Stores heading values sent from the phone and clears them after a short timeout.
 */
object PhoneHeadingBridge {
    private const val STALE_AFTER_MS: Long = 2_000L

    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val headingState = MutableStateFlow<PhoneHeading?>(null)

    private var expiryJob: Job? = null

    fun heading(): StateFlow<PhoneHeading?> = headingState.asStateFlow()

    fun updateHeading(azDeg: Double, timestampMs: Long) {
        val normalized = normalizeDeg(azDeg.toFloat())
        headingState.value = PhoneHeading(
            azimuthDeg = normalized,
            timestampMs = timestampMs,
        )
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay(STALE_AFTER_MS)
            headingState.value = null
        }
    }

    private fun normalizeDeg(value: Float): Float {
        val normalized = floorMod(value.toInt(), 360)
        val fraction = value - value.toInt()
        return normalized + fraction
    }
}

data class PhoneHeading(
    val azimuthDeg: Float,
    val timestampMs: Long,
)
