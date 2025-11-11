package dev.pointtosky.wear.sensors.orientation

import dev.pointtosky.wear.datalayer.PhoneHeadingBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

class PhoneHeadingOverrideRepository(
    private val delegate: OrientationRepository,
    phoneHeadingBridge: PhoneHeadingBridge = PhoneHeadingBridge,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : OrientationRepository by delegate {

    private val headingFlow = phoneHeadingBridge.heading()

    override val frames: Flow<OrientationFrame> = combine(
        delegate.frames,
        headingFlow,
        delegate.zero,
    ) { frame, heading, zero ->
        val override = heading ?: return@combine frame
        val adjustedAzimuth = normalizeDeg(override.azimuthDeg - zero.azimuthOffsetDeg)
        frame.copy(
            azimuthDeg = adjustedAzimuth,
            accuracy = OrientationAccuracy.MEDIUM,
        )
    }

    override val activeSource = combine(
        delegate.activeSource,
        headingFlow,
    ) { source, heading ->
        if (heading != null) OrientationSource.PHONE else source
    }.stateIn(scope, SharingStarted.Eagerly, delegate.activeSource.value)

    override val source: OrientationSource
        get() = if (headingFlow.value != null) OrientationSource.PHONE else delegate.source

    private fun normalizeDeg(value: Float): Float {
        var normalized = value % 360f
        if (normalized < 0f) {
            normalized += 360f
        }
        return normalized
    }
}
