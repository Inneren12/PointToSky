package dev.pointtosky.wear.sensors.orientation

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

internal class DelegatingOrientationRepository(
    private val primary: OrientationRepository?,
    private val fallback: OrientationRepository?,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : OrientationRepository {

    private val activeRepository = MutableStateFlow<OrientationRepository?>(null)

    override val frames: Flow<OrientationFrame> = activeRepository
        .filterNotNull()
        .flatMapLatest { repository -> repository.frames }

    override val zero: StateFlow<OrientationZero> = activeRepository
        .filterNotNull()
        .flatMapLatest { repository -> repository.zero }
        .stateIn(scope, SharingStarted.Eagerly, OrientationZero())

    override val fps: StateFlow<Float?> = activeRepository
        .filterNotNull()
        .flatMapLatest { repository -> repository.fps }
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _activeSource: StateFlow<OrientationSource> = activeRepository
        .filterNotNull()
        .map { repository -> repository.source }
        .stateIn(
            scope,
            SharingStarted.Eagerly,
            primary?.source
                ?: fallback?.source
                ?: throw IllegalStateException("No orientation sources available"),
        )

    override val activeSource: StateFlow<OrientationSource> = _activeSource

    override val source: OrientationSource
        get() = activeSource.value

    override fun start() {
        val target = primary ?: fallback
            ?: throw IllegalStateException("No orientation sources available")
        if (activeRepository.value != target) {
            activeRepository.value?.stop()
            activeRepository.value = target
        }
        target.start()
    }

    override fun stop() {
        activeRepository.value?.stop()
    }

    override fun updateZero(orientationZero: OrientationZero) {
        val target = activeRepository.value
        if (target != null) {
            target.updateZero(orientationZero)
        } else {
            primary?.updateZero(orientationZero)
            fallback?.updateZero(orientationZero)
        }
    }

    override fun setZeroAzimuthOffset(offsetDeg: Float) {
        val target = activeRepository.value
        if (target != null) {
            target.setZeroAzimuthOffset(offsetDeg)
        } else {
            primary?.setZeroAzimuthOffset(offsetDeg)
            fallback?.setZeroAzimuthOffset(offsetDeg)
        }
    }

    override fun setRemap(screenRotation: ScreenRotation) {
        val target = activeRepository.value
        if (target != null) {
            target.setRemap(screenRotation)
        } else {
            primary?.setRemap(screenRotation)
            fallback?.setRemap(screenRotation)
        }
    }

    override fun resetZero() {
        val target = activeRepository.value
        if (target != null) {
            target.resetZero()
        } else {
            primary?.resetZero()
            fallback?.resetZero()
        }
    }
}
