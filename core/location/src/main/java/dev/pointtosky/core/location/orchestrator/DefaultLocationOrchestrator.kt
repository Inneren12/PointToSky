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
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import java.util.concurrent.atomic.AtomicReference

class DefaultLocationOrchestrator(
    private val fused: LocationRepository?,
    private val manualPrefs: LocationPrefs,
    private val remotePhone: LocationRepository?,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val manualReemitIntervalMs: Long = DEFAULT_MANUAL_REEMIT_INTERVAL_MS,
) : LocationOrchestrator {

    // Не считаем fused "доступным" по факту наличия инстанса.
    // Включаем только после первого реального эмита; ошибки/завершение — выключаем.
    private val fusedAvailable = MutableStateFlow(false)

    private val latestFix = AtomicReference<LocationFix?>(null)

    private val fusedFixes: Flow<LocationFix> = (fused?.fixes ?: emptyFlow())
        .onEach {
            if (!fusedAvailable.value) fusedAvailable.value = true
        }
        .catch { throwable ->
            if (throwable is SecurityException) {
                fusedAvailable.value = false
            } else {
                throw throwable
            }
        }
        .onCompletion { cause ->
            if (cause != null) fusedAvailable.value = false
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
        .onEach { fix -> latestFix.set(fix) }

    override suspend fun start(config: LocationConfig) {
        // Не поднимаем доступность оптимистически
        fusedAvailable.value = false
        try {
            fused?.start(config)
        } catch (_: SecurityException) {
            fusedAvailable.value = false
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
            return manualPoint.toManualFix().also { latestFix.set(it) }
        }
        val useFallback = manualPrefs.usePhoneFallbackFlow.first()
        latestFix.get()?.let { cached ->
            if (cached.provider != ProviderType.MANUAL &&
                (cached.provider != ProviderType.REMOTE_PHONE || useFallback)
            ) {
                return cached
            }
        }
        fused?.getLastKnown()?.let { fix ->
            latestFix.set(fix)
            return fix
        }
        if (!useFallback) return null
        val remoteLast = remotePhone?.getLastKnown()
        if (remoteLast != null) {
            latestFix.set(remoteLast)
        }
        return remoteLast
    }

    override suspend fun setManual(point: GeoPoint?) {
        manualPrefs.setManual(point)
        if (point == null) {
            val cached = latestFix.get()
            if (cached?.provider == ProviderType.MANUAL) {
                latestFix.set(null)
            }
        }
    }

    override suspend fun preferPhoneFallback(enabled: Boolean) {
        manualPrefs.setUsePhoneFallback(enabled)
    }

    private fun manualFixFlow(point: GeoPoint): Flow<LocationFix> = flow {
        emit(point.toManualFix())
        if (manualReemitIntervalMs <= 0L) return@flow
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
