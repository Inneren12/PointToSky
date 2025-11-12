package dev.pointtosky.mobile.card

import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.coord.Horizontal
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant

object CardRepository {
    sealed class Entry {
        data class Ready(val model: CardObjectModel) : Entry()

        data class Invalid(val reason: String? = null) : Entry()
    }

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())
    private val latestIdState = MutableStateFlow<String?>(null)

    fun observe(id: String): Flow<Entry?> =
        entries
            .map { it[id] }
            .distinctUntilChanged()

    fun latestCardIdFlow(): StateFlow<String?> = latestIdState.asStateFlow()

    fun latestCardId(): String? = latestIdState.value

    fun update(
        id: String,
        entry: Entry,
    ) {
        entries.update { current ->
            val mutable = if (current.isEmpty()) mutableMapOf() else current.toMutableMap()
            mutable[id] = entry
            mutable
        }
        latestIdState.value = id
    }

    @androidx.annotation.VisibleForTesting
    fun resetForTests() {
        entries.value = emptyMap()
        latestIdState.value = null
    }
}

data class CardObjectModel(
    val id: String,
    val type: CardObjectType,
    val name: String?,
    val body: String?,
    val constellation: String?,
    val magnitude: Double?,
    val equatorial: Equatorial?,
    val horizontal: Horizontal?,
    val bestWindow: CardBestWindow?,
)

data class CardBestWindow(
    val start: Instant?,
    val end: Instant?,
)
