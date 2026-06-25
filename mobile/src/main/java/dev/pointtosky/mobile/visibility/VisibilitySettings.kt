package dev.pointtosky.mobile.visibility

import androidx.annotation.VisibleForTesting
import dev.pointtosky.core.astro.visibility.Bortle
import kotlinx.coroutines.flow.MutableStateFlow

/** Process-scoped, session-only visibility settings shared by AR and the sky map (mirrors the
 *  CatalogRepositoryProvider singleton pattern). In-memory by design — not persisted. */
object VisibilitySettings {
    val enabled = MutableStateFlow(false)
    val bortle = MutableStateFlow(Bortle.CLASS_4)

    @VisibleForTesting
    fun reset() {
        enabled.value = false
        bortle.value = Bortle.CLASS_4
    }
}
