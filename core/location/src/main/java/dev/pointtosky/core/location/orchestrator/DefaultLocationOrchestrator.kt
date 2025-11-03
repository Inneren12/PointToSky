package dev.pointtosky.core.location.orchestrator

import dev.pointtosky.core.location.api.LocationConfig
import dev.pointtosky.core.location.api.LocationOrchestrator
import dev.pointtosky.core.location.api.LocationRepository
import dev.pointtosky.core.location.model.GeoPoint
import dev.pointtosky.core.location.model.LocationFix
import dev.pointtosky.core.location.model.ProviderType
import dev.pointtosky.core.location.prefs.LocationPrefs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart

class DefaultLocationOrchestrator(
    private val fused: LocationRepository?,
    private val manualPrefs: LocationPrefs,
    private val remotePhone: LocationRepository?,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val manualReemitIntervalMs: Long = DEFAULT_MANUAL_REEMIT_INTERVAL_MS,
) : LocationOrchestrator {

    private val fusedAvailable = MutableStateFlow(fused != null)

    private val fusedFixes: Flow<LocationFix> = (fused?.fixes ?: emptyFlow())
        .onStart { fusedAvailable.value = fused != null }
        .catch { throwable ->
            if (throwable is SecurityException) {
                fusedAvailable.value = false
            } else {
                throw throwable
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

    override suspend fun start(config: LocationConfig) {
        fused?.let { repository ->
            fusedAvailable.value = try {
                repository.start(config)
                true
            } catch (security: SecurityException) {
                fusedAvailable.value = false
                false
            }
        }
        remotePhone?.start(config)
    }

    override suspend fun stop() {
        fused?.stop()
        remotePhone?.stop()
    }

    override suspend fun getLastKnown(): LocationFix? {
        val manualPoint = manualPrefs.manualPointFlow.firstOrNull()
        if (manualPoint != null) {
            return manualPoint.toManualFix()
        }
        val fusedLast = fused?.getLastKnown()
        if (fusedLast != null) {
            return fusedLast
        }
        val useFallback = manualPrefs.usePhoneFallbackFlow.first()
        if (!useFallback) {
            return null
        }
        return remotePhone?.getLastKnown()
    }

    override suspend fun setManual(point: GeoPoint?) {
        manualPrefs.setManual(point)
    }

    override suspend fun preferPhoneFallback(enabled: Boolean) {
        manualPrefs.setUsePhoneFallback(enabled)
    }

    private fun manualFixFlow(point: GeoPoint): Flow<LocationFix> = flow {
        emit(point.toManualFix())
        if (manualReemitIntervalMs <= 0L) {
            return@flow
        }
        while (true) {
            delay(manualReemitIntervalMs)
            emit(point.toManualFix())
        }
    }

    private fun GeoPoint.toManualFix(): LocationFix = LocationFix(
        point = this,
        timeMs = clock(),
        accuracyM = 0f,
        provider = ProviderType.MANUAL,
    )

    companion object {
        private const val DEFAULT_MANUAL_REEMIT_INTERVAL_MS = 5 * 60 * 1000L
    }
}
