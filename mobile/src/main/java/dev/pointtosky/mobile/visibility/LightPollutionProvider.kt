package dev.pointtosky.mobile.visibility

import android.content.Context
import dev.pointtosky.core.astro.visibility.LightPollutionGrid
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object LightPollutionProvider {
    private val grid = MutableStateFlow<LightPollutionGrid?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    fun ensureLoaded(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            val app = context.applicationContext
            scope.launch {
                try {
                    val bytes = app.assets.open("lightpollution/bortle.bin").use { it.readBytes() }
                    grid.value = LightPollutionGrid.parse(bytes)
                } catch (_: Exception) {
                    // Missing or corrupt asset — auto-Bortle stays unavailable; app uses manual fallback.
                }
            }
        }
    }

    fun grid(): StateFlow<LightPollutionGrid?> = grid.asStateFlow()
}
