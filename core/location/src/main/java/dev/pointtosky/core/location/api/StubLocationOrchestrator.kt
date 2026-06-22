package dev.pointtosky.core.location.api

import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.location.model.ProviderType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge

class StubLocationOrchestrator(
    private val delegate: LocationRepository,
    private val timestampProvider: () -> Long = { System.currentTimeMillis() },
) : LocationOrchestrator {

    private val manualFix = MutableStateFlow<LocationFix?>(null)
    private val phoneFallbackPreferred = MutableStateFlow(false)

    override val fixes: Flow<LocationFix> = merge(
        delegate.fixes,
        manualFix.filterNotNull(),
    )

    // When delegate is a LocationOrchestrator its currentFix is stateful: combine buffers its last
    // value so "manual cleared, delegate quiet" immediately re-emits the cached fix without waiting
    // for a new delegate emission. For a plain LocationRepository, seed from getLastKnown() on each
    // (re-)subscription so a quiet-but-cached delegate surfaces its fix immediately.
    override val currentFix: Flow<LocationFix?> = if (delegate is LocationOrchestrator) {
        combine(manualFix, delegate.currentFix) { manual, delegateCurrent ->
            manual ?: delegateCurrent
        }.distinctUntilChanged()
    } else {
        manualFix.flatMapLatest { manual ->
            if (manual != null) {
                flowOf<LocationFix?>(manual)
            } else {
                flow<LocationFix?> {
                    emit(delegate.getLastKnown())
                    delegate.fixes.collect { emit(it) }
                }
            }
        }.distinctUntilChanged()
    }

    override suspend fun start(config: LocationConfig) {
        delegate.start(config)
    }

    override suspend fun stop() {
        delegate.stop()
    }

    override suspend fun getLastKnown(): LocationFix? = manualFix.value ?: delegate.getLastKnown()

    override suspend fun setManual(point: GeoPoint?) {
        manualFix.value = point?.let {
            LocationFix(
                point = it,
                timeMs = timestampProvider(),
                accuracyM = 0f,
                provider = ProviderType.MANUAL,
            )
        }
    }

    override suspend fun preferPhoneFallback(enabled: Boolean) {
        phoneFallbackPreferred.value = enabled
    }

    fun isPhoneFallbackPreferred(): Boolean = phoneFallbackPreferred.value
}
