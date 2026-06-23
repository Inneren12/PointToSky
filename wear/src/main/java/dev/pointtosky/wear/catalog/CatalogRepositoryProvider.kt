package dev.pointtosky.wear.catalog

import android.content.Context
import dev.pointtosky.core.catalog.runtime.CatalogLoadState
import dev.pointtosky.core.catalog.runtime.CatalogRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object CatalogRepositoryProvider {
    private val state = MutableStateFlow<CatalogLoadState>(CatalogLoadState.Loading)
    // App-scoped: the catalog is a process-lifetime singleton; this one-shot load never needs cancelling.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var started = false

    /** Idempotently kicks off the (heavy, blocking) catalog load off the main thread. */
    fun ensureLoaded(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
            val appContext = context.applicationContext
            scope.launch {
                val repo = CatalogRepository.create(appContext) // blocking I/O + parse, on IO
                state.value = CatalogLoadState.Ready(repo)
            }
        }
    }

    fun state(): StateFlow<CatalogLoadState> = state.asStateFlow()
}
