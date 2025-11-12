package dev.pointtosky.mobile.search

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import dev.pointtosky.core.astro.coord.Equatorial
import dev.pointtosky.core.astro.ephem.Body
import dev.pointtosky.core.astro.ephem.EphemerisComputer
import dev.pointtosky.core.astro.ephem.SimpleEphemerisComputer
import dev.pointtosky.core.catalog.runtime.CatalogRepository
import dev.pointtosky.core.catalog.star.Star
import dev.pointtosky.mobile.R
import dev.pointtosky.mobile.card.CardObjectModel
import dev.pointtosky.mobile.card.CardObjectType
import dev.pointtosky.mobile.card.CardRepository
import dev.pointtosky.mobile.logging.MobileLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.Normalizer
import java.time.Instant
import java.util.Locale

class SearchViewModel(
    context: Context,
    private val catalogRepository: CatalogRepository,
    private val cardRepository: CardRepository,
    private val ephemerisComputer: EphemerisComputer = SimpleEphemerisComputer(),
    private val clock: () -> Instant = { Instant.now() },
    private val backgroundDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val appContext = context.applicationContext

    private val planetNames = mapOf(
        Body.SUN to appContext.getString(R.string.search_planet_sun),
        Body.MOON to appContext.getString(R.string.search_planet_moon),
        Body.JUPITER to appContext.getString(R.string.search_planet_jupiter),
        Body.SATURN to appContext.getString(R.string.search_planet_saturn),
    )

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<SearchEvent>()
    val events: SharedFlow<SearchEvent> = _events.asSharedFlow()

    private var index: SearchIndex? = null
    private var pendingQuery: String = ""

    init {
        viewModelScope.launch(backgroundDispatcher) {
            val entries = buildEntries()
            index = SearchIndex(entries)
            val query = pendingQuery
            val results = if (query.isNotBlank()) {
                index?.search(query, MAX_RESULTS).orEmpty()
            } else {
                emptyList()
            }
            _state.update { current ->
                current.copy(
                    results = results,
                    loading = false,
                )
            }
        }
    }

    fun onQueryChange(query: String) {
        pendingQuery = query
        val trimmed = query.trim()
        val currentIndex = index
        val matches = if (currentIndex != null && trimmed.isNotBlank()) {
            currentIndex.search(trimmed, MAX_RESULTS)
        } else {
            emptyList()
        }
        if (trimmed.isNotEmpty()) {
            MobileLog.searchQuery(trimmed, matches.size)
        }
        _state.update { current ->
            current.copy(
                query = query,
                results = matches,
                loading = currentIndex == null,
            )
        }
    }

    fun onResultSelected(result: SearchResult) {
        viewModelScope.launch(backgroundDispatcher) {
            val cardId = prepareCardEntry(result)
            _events.emit(SearchEvent.OpenCard(cardId))
        }
    }

    private fun prepareCardEntry(result: SearchResult): String {
        return when (val payload = result.payload) {
            is SearchPayload.Star -> {
                val eq = Equatorial(payload.star.raDeg, payload.star.decDeg)
                val model = CardObjectModel(
                    id = payload.star.id.toString(),
                    type = CardObjectType.STAR,
                    name = result.title,
                    body = null,
                    constellation = payload.star.constellation,
                    magnitude = payload.star.magnitude,
                    equatorial = eq,
                    horizontal = null,
                    bestWindow = null,
                )
                cardRepository.update(model.id, CardRepository.Entry.Ready(model))
                MobileLog.cardOpen(source = "search", id = model.id, type = model.type.name)
                model.id
            }

            is SearchPayload.Planet -> {
                val ephemeris = ephemerisComputer.compute(payload.body, clock())
                val constellation = catalogRepository.constellationBoundaries.findByEq(ephemeris.eq)
                val type = when (payload.body) {
                    Body.MOON -> CardObjectType.MOON
                    else -> CardObjectType.PLANET
                }
                val model = CardObjectModel(
                    id = payload.body.name,
                    type = type,
                    name = result.title,
                    body = payload.body.name,
                    constellation = constellation,
                    magnitude = null,
                    equatorial = ephemeris.eq,
                    horizontal = null,
                    bestWindow = null,
                )
                cardRepository.update(model.id, CardRepository.Entry.Ready(model))
                MobileLog.cardOpen(source = "search", id = model.id, type = model.type.name)
                model.id
            }
        }
    }

    private fun buildEntries(): List<SearchEntry> {
        val stars = loadStars()
        val starEntries = stars.map { star ->
            val aliases = buildStarAliases(star)
            val subtitle = buildStarSubtitle(star)
            val trailing = star.magnitude?.let { magnitudeLabel(it) }
            SearchEntry(
                cardId = star.id.toString(),
                title = star.displayName,
                subtitle = subtitle,
                trailing = trailing,
                payload = SearchPayload.Star(star),
                aliases = aliases,
                magnitude = star.magnitude,
                priority = STAR_PRIORITY,
            )
        }
        val planetEntries = Body.values()
            .mapNotNull { body ->
                val name = planetNames[body] ?: return@mapNotNull null
                val aliases = listOf(name, body.name)
                    .mapNotNull { alias -> alias.takeIf { it.isNotBlank() } }
                    .distinct()
                    .map { alias -> Alias(alias, normalize(alias)) }
                SearchEntry(
                    cardId = body.name,
                    title = name,
                    subtitle = null,
                    trailing = null,
                    payload = SearchPayload.Planet(body),
                    aliases = aliases,
                    magnitude = null,
                    priority = PLANET_PRIORITY,
                )
            }
        return planetEntries + starEntries
    }

    private fun loadStars(): List<SearchStar> {
        val stars = catalogRepository.starCatalog.nearby(
            center = Equatorial(0.0, 0.0),
            radiusDeg = 180.0,
            magLimit = STAR_MAG_LIMIT,
        )
        return stars
            .distinctBy(Star::id)
            .mapNotNull { star ->
                val magnitude = star.mag.toDouble().takeIf { !it.isNaN() }
                val displayName = primaryStarName(star) ?: star.id.toString()
                val constellation = star.constellation?.takeIf { it.isNotBlank() }
                SearchStar(
                    id = star.id,
                    displayName = displayName,
                    name = star.name?.takeIf { it.isNotBlank() },
                    bayer = star.bayer?.takeIf { it.isNotBlank() },
                    flamsteed = star.flamsteed?.takeIf { it.isNotBlank() },
                    constellation = constellation,
                    magnitude = magnitude,
                    raDeg = star.raDeg.toDouble(),
                    decDeg = star.decDeg.toDouble(),
                )
            }
    }

    private fun primaryStarName(star: Star): String? {
        return when {
            !star.name.isNullOrBlank() -> star.name
            !star.bayer.isNullOrBlank() -> star.bayer
            !star.flamsteed.isNullOrBlank() -> star.flamsteed
            else -> null
        }
    }

    private fun buildStarAliases(star: SearchStar): List<Alias> {
        val aliases = linkedSetOf<String>()
        star.name?.let { aliases += it }
        star.bayer?.let { aliases += it }
        star.flamsteed?.let { aliases += it }
        aliases += star.displayName
        star.constellation?.let { aliases += it }
        aliases += star.id.toString()
        return aliases
            .mapNotNull { alias -> alias.takeIf { it.isNotBlank() } }
            .map { alias -> Alias(alias, normalize(alias)) }
            .filter { it.normalized.isNotBlank() }
    }

    private fun buildStarSubtitle(star: SearchStar): String? {
        val extra = mutableListOf<String>()
        star.name?.let { extra += it }
        star.bayer?.let { extra += it }
        star.flamsteed?.let { extra += it }
        val distinct = extra.distinct().filter { it != star.displayName }
        val pieces = distinct.toMutableList()
        star.constellation?.let { pieces += it }
        return pieces.takeIf { it.isNotEmpty() }?.joinToString(" • ")
    }

    private fun magnitudeLabel(magnitude: Double): String {
        return String.format(Locale.ROOT, "m %.1f", magnitude)
    }

    private data class SearchEntry(
        val cardId: String,
        val title: String,
        val subtitle: String?,
        val trailing: String?,
        val payload: SearchPayload,
        val aliases: List<Alias>,
        val magnitude: Double?,
        val priority: Int,
    )

    private class SearchIndex(
        private val entries: List<SearchEntry>,
    ) {
        fun search(query: String, limit: Int): List<SearchResult> {
            val normalizedQuery = normalize(query)
            if (normalizedQuery.isEmpty()) return emptyList()
            val matches = mutableListOf<SearchMatch>()
            for (entry in entries) {
                val score = entry.matchScore(normalizedQuery) ?: continue
                matches += SearchMatch(entry, score)
            }
            matches.sortWith(
                compareBy<SearchMatch>({
                    it.score
                }, { it.entry.priority }, { it.entry.magnitude ?: Double.MAX_VALUE }, { it.entry.title }),
            )
            return matches.take(limit).map { match ->
                val entry = match.entry
                SearchResult(
                    cardId = entry.cardId,
                    title = entry.title,
                    subtitle = entry.subtitle,
                    trailing = entry.trailing,
                    payload = entry.payload,
                )
            }
        }

        private fun SearchEntry.matchScore(query: String): Int? {
            var best: Int? = null
            for (alias in aliases) {
                val candidate = alias.normalized
                if (candidate.isEmpty()) continue
                val score = score(candidate, query)
                if (score != null) {
                    best = best?.let { kotlin.math.min(it, score) } ?: score
                }
            }
            return best
        }

        private fun score(candidate: String, query: String): Int? {
            val index = candidate.indexOf(query)
            if (index >= 0) {
                return when (index) {
                    0 -> if (candidate.length == query.length) 0 else 1
                    else -> 2 + index
                }
            }
            val distance = levenshtein(candidate, query)
            return 10 + distance
        }
    }

    data class SearchResult(
        val cardId: String,
        val title: String,
        val subtitle: String?,
        val trailing: String?,
        val payload: SearchPayload,
    )

    data class SearchUiState(
        val query: String = "",
        val results: List<SearchResult> = emptyList(),
        val loading: Boolean = true,
    )

    sealed interface SearchPayload {
        data class Star(val star: SearchStar) : SearchPayload
        data class Planet(val body: Body) : SearchPayload
    }

    data class SearchStar(
        val id: Int,
        val displayName: String,
        val name: String?,
        val bayer: String?,
        val flamsteed: String?,
        val constellation: String?,
        val magnitude: Double?,
        val raDeg: Double,
        val decDeg: Double,
    )

    private data class Alias(val original: String, val normalized: String)

    private data class SearchMatch(val entry: SearchEntry, val score: Int)

    sealed interface SearchEvent {
        data class OpenCard(val cardId: String) : SearchEvent
    }

    class Factory(
        private val context: Context,
        private val catalogRepository: CatalogRepository,
        private val cardRepository: CardRepository,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(SearchViewModel::class.java)) {
                @Suppress("UNCHECKED_CAST")
                return SearchViewModel(context, catalogRepository, cardRepository) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class ${modelClass.name}")
        }
    }

    companion object {
        private const val MAX_RESULTS = 50
        private const val STAR_PRIORITY = 1
        private const val PLANET_PRIORITY = 0
        private const val STAR_MAG_LIMIT = 6.0
        private fun normalize(input: String): String {
            if (input.isBlank()) return ""
            val lower = input.lowercase(Locale.ROOT)
                .replace('\u2019', '\'')
                .replace('-', ' ')
                .replace('_', ' ')
            val decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD)
            val builder = StringBuilder(decomposed.length)
            for (ch in decomposed) {
                val type = Character.getType(ch)
                if (type == Character.NON_SPACING_MARK.toInt()) continue
                builder.append(
                    when (ch) {
                        'ё' -> 'е'
                        else -> ch
                    },
                )
            }
            return WHITESPACE_REGEX.replace(builder.toString(), " ").trim()
        }

        private val WHITESPACE_REGEX = Regex("\\s+")

        private fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            val previous = IntArray(b.length + 1) { it }
            val current = IntArray(b.length + 1)
            for (i in 1..a.length) {
                current[0] = i
                val ac = a[i - 1]
                for (j in 1..b.length) {
                    val cost = if (ac == b[j - 1]) 0 else 1
                    current[j] = minOf(
                        previous[j] + 1,
                        current[j - 1] + 1,
                        previous[j - 1] + cost,
                    )
                }
                System.arraycopy(current, 0, previous, 0, previous.size)
            }
            return previous[b.length]
        }
    }
}
