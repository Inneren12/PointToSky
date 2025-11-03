package dev.pointtosky.core.location.orchestrator

import dev.pointtosky.core.location.api.LocationConfig
import dev.pointtosky.core.location.api.LocationOrchestrator
import dev.pointtosky.core.location.api.LocationRepository
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.location.model.ProviderType
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach

class DefaultLocationOrchestrator(
    private val fused: LocationRepository?,
    private val remotePhone: LocationRepository?,
    private val manualPrefs: ManualLocationPreferences,
    private val timestampProvider: () -> Long = System::currentTimeMillis,
) : LocationOrchestrator {

    // Presence of a fused repo != availability. Start pessimistic (false) and flip to true only on real fixes.
    private val fusedAvailable = MutableStateFlow(false)

    private val latestFix = AtomicReference<LocationFix?>(null)

    private val fusedFixes: Flow<LocationFix> = (fused?.fixes ?: emptyFlow())
        // Declare availability only after the first actual emission
        .onEach {
            if (!fusedAvailable.value) {
                fusedAvailable.value = true
            }
        }
        .catch { throwable ->
            if (throwable is SecurityException) {
                fusedAvailable.value = false
            } else {
                throw throwable
            }
        }
        // If upstream completes with error/cancel, treat as unavailable
        .onCompletion { cause ->
            if (cause != null) {
                fusedAvailable.value = false
            }
        }

    private val remoteFixes: Flow<LocationFix> = remotePhone?.fixes ?: emptyFlow()

    override val fixes: Flow<LocationFix> = manualPrefs.manualPointFlow
        .distinctUntilChanged()
        .flatMapLatest { manualPoint ->
            if (manualPoint != null) {
                manualFixFlow(manualPoint)
            } else {
                val remoteFallback = manualPrefs.usePhoneFallbackFlow
                    .combine(fusedAvailable) { useFallback, isFusedAvailable ->
                        useFallback && !isFusedAvailable
                    }
                    .flatMapLatest { shouldUseRemote ->
                        if (shouldUseRemote) remoteFixes else emptyFlow()
                    }
                merge(fusedFixes, remoteFallback)
            }
        }
        .onEach { latestFix.set(it) }

    override suspend fun start(config: LocationConfig) {
        fusedAvailable.value = false
        try {
            fused?.start(config)
        } catch (se: SecurityException) {
            fusedAvailable.value = false
        }
        remotePhone?.start(config)
    }

    override suspend fun stop() {
        fused?.stop()
        remotePhone?.stop()
    }

    override suspend fun getLastKnown(): LocationFix? {
        val manualPoint = manualPrefs.manualPointFlow.first()
        if (manualPoint != null) {
            return createManualFix(manualPoint)
        }
        latestFix.get()?.let { return it }
        fused?.getLastKnown()?.let { return it }
        return remotePhone?.getLastKnown()
    }

    override fun setManual(point: GeoPoint?) {
        manualPrefs.setManualPoint(point)
    }

    override fun preferPhoneFallback(enabled: Boolean) {
        manualPrefs.setUsePhoneFallback(enabled)
    }

    private fun manualFixFlow(point: GeoPoint): Flow<LocationFix> = flowOf(createManualFix(point))

    private fun createManualFix(point: GeoPoint): LocationFix = LocationFix(
        point = point,
        timeMs = timestampProvider(),
        accuracyM = 0f,
        provider = ProviderType.MANUAL,
    )
}

interface ManualLocationPreferences {
    val manualPointFlow: Flow<GeoPoint?>
    val usePhoneFallbackFlow: Flow<Boolean>

    fun setManualPoint(point: GeoPoint?)
    fun setUsePhoneFallback(enabled: Boolean)
}
