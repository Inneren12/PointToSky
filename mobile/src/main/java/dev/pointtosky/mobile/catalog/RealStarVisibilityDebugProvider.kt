package dev.pointtosky.mobile.catalog

import android.content.Context
import androidx.annotation.VisibleForTesting
import dev.pointtosky.core.catalog.binary.AssetRealStarCatalogProvider
import dev.pointtosky.core.catalog.io.AndroidAssetProvider
import dev.pointtosky.core.catalog.visibility.RealStarVisibilityService
import dev.pointtosky.core.catalog.visibility.SkyQualityInput
import dev.pointtosky.core.catalog.visibility.debug.RealStarVisibilityDebugInfo
import dev.pointtosky.core.catalog.visibility.debug.RealStarVisibilityDebugProbe
import dev.pointtosky.core.catalog.visibility.debug.RealStarVisibilityDebugSnapshot
import dev.pointtosky.mobile.logging.MobileLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** VF-1f: observable mirror of the latest [RealStarVisibilityDebugSnapshot], for debug UI. */
sealed interface RealStarVisibilityDebugUiState {
    data object Loading : RealStarVisibilityDebugUiState
    data class Success(val info: RealStarVisibilityDebugInfo) : RealStarVisibilityDebugUiState
    data class Failure(val message: String) : RealStarVisibilityDebugUiState
}

/**
 * VF-1e/VF-1f: one-shot, non-rendering runtime check that the PTSKCAT0
 * real-star pipeline (VF-1a..d) loads the bundled catalog and computes a
 * visible-count snapshot. Logs the result via [MobileLog] and exposes it as
 * [state] for debug UI; does not feed the sky renderer or the PTSKCAT4
 * constellation-art catalog — those stay untouched.
 *
 * Uses a conservative manual default ([DEFAULT_INPUT]); no GPS, no
 * automatic skyglow/grid lookup, no Moon/twilight, no camera matching.
 */
object RealStarVisibilityDebugProvider {
    private val DEFAULT_INPUT = SkyQualityInput.Bortle(5)

    // One-shot check: no state is retained beyond the log event and the
    // latest snapshot below, so this needs no lifecycle-scoped cache.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<RealStarVisibilityDebugUiState>(RealStarVisibilityDebugUiState.Loading)
    val state: StateFlow<RealStarVisibilityDebugUiState> = _state.asStateFlow()

    @Volatile
    private var started = false

    /** Idempotently runs the one-shot real-star visibility check off the main thread. */
    fun ensureLoaded(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            val appContext = context.applicationContext
            scope.launch {
                applySnapshot {
                    val provider = AssetRealStarCatalogProvider(AndroidAssetProvider(appContext))
                    val service = RealStarVisibilityService(provider)
                    RealStarVisibilityDebugProbe(service).snapshot(DEFAULT_INPUT)
                }
            }
        }
    }

    /**
     * Computes a snapshot via [compute], logs it, and publishes it to [state].
     * Exposed for tests so the state-update/logging behavior can be verified
     * without an Android [Context] or a real PTSKCAT0 asset.
     */
    @VisibleForTesting
    internal fun applySnapshot(compute: () -> RealStarVisibilityDebugSnapshot) {
        val snapshot =
            try {
                compute()
            } catch (e: Exception) {
                // Startup probe must never crash the app; RealStarCatalogLoadException is
                // already handled as a Failure snapshot by the probe itself, this guards
                // against anything else escaping provider construction or service.select().
                RealStarVisibilityDebugSnapshot.Failure(
                    "Unexpected real-star visibility debug probe failure: ${e::class.simpleName}: ${e.message}",
                )
            }
        when (snapshot) {
            is RealStarVisibilityDebugSnapshot.Success -> {
                MobileLog.realStarVisibilityDebug(snapshot.info)
                _state.value = RealStarVisibilityDebugUiState.Success(snapshot.info)
            }
            is RealStarVisibilityDebugSnapshot.Failure -> {
                MobileLog.realStarVisibilityDebugFailed(snapshot.message)
                _state.value = RealStarVisibilityDebugUiState.Failure(snapshot.message)
            }
        }
    }
}
