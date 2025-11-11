package dev.pointtosky.wear.tile.tonight

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant

/**
 * Debug helper for Tonight tile: keeps the last generated model metadata for UI.
 */
object TonightTileDebug {
    private val _state = MutableStateFlow<TileGenerationInfo?>(null)
    val state: StateFlow<TileGenerationInfo?> = _state.asStateFlow()

    fun update(model: TonightTileModel) {
        _state.value = TileGenerationInfo(
            generatedAt = model.updatedAt,
            topTargetTitle = model.items.firstOrNull()?.title,
        )
    }
}

data class TileGenerationInfo(
    val generatedAt: Instant,
    val topTargetTitle: String?,
)
