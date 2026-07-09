package dev.pointtosky.mobile.catalog

import android.content.Context
import dev.pointtosky.core.catalog.binary.AssetRealStarCatalogProvider
import dev.pointtosky.core.catalog.io.AndroidAssetProvider
import dev.pointtosky.core.catalog.visibility.RealStarVisibilityService
import dev.pointtosky.core.catalog.visibility.SkyQualityInput
import dev.pointtosky.core.catalog.visibility.debug.RealStarVisibilityDebugProbe
import dev.pointtosky.core.catalog.visibility.debug.RealStarVisibilityDebugSnapshot
import dev.pointtosky.mobile.logging.MobileLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * VF-1e: one-shot, non-rendering runtime check that the PTSKCAT0 real-star
 * pipeline (VF-1a..d) loads the bundled catalog and computes a visible-count
 * snapshot. Logs the result via [MobileLog] and does not feed the sky
 * renderer or the PTSKCAT4 constellation-art catalog — those stay untouched.
 *
 * Uses a conservative manual default ([DEFAULT_INPUT]); no GPS, no
 * automatic skyglow/grid lookup, no Moon/twilight, no camera matching.
 */
object RealStarVisibilityDebugProvider {
    private val DEFAULT_INPUT = SkyQualityInput.Bortle(5)

    // One-shot check: no state is retained beyond the log event, so this needs
    // no lifecycle-scoped cache.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                try {
                    val provider = AssetRealStarCatalogProvider(AndroidAssetProvider(appContext))
                    val service = RealStarVisibilityService(provider)
                    val probe = RealStarVisibilityDebugProbe(service)
                    when (val snapshot = probe.snapshot(DEFAULT_INPUT)) {
                        is RealStarVisibilityDebugSnapshot.Success -> MobileLog.realStarVisibilityDebug(snapshot.info)
                        is RealStarVisibilityDebugSnapshot.Failure -> MobileLog.realStarVisibilityDebugFailed(snapshot.message)
                    }
                } catch (e: Exception) {
                    // Startup probe must never crash the app; RealStarCatalogLoadException is
                    // already handled as a Failure snapshot above, this guards against anything
                    // else escaping provider construction, service.select(), or logging.
                    MobileLog.realStarVisibilityDebugFailed(
                        "Unexpected real-star visibility debug probe failure: ${e::class.simpleName}: ${e.message}",
                    )
                }
            }
        }
    }
}
