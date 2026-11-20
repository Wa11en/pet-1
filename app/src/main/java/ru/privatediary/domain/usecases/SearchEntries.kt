package ru.privatediary.domain.usecases

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import ru.privatediary.domain.EntryRepository
import ru.privatediary.domain.models.SearchResult
import ru.privatediary.domain.models.SearchFilters
import ru.privatediary.domain.models.Entry
import ru.privatediary.util.WordSearch
import java.time.ZoneId

/** Semantic implementation is injected; null means unavailable and enables the FTS fallback. */
class SearchEntries(
    private val repository: EntryRepository,
    private val semanticSearch: suspend (String, SearchFilters) -> List<SearchResult>?,
) {
    suspend operator fun invoke(query: String, byMeaning: Boolean, filters: SearchFilters = SearchFilters()): List<SearchResult> {
        if (query.isBlank()) {
            if (filters.isEmpty) return emptyList()
            val zone = ZoneId.systemDefault()
            val preview = WordSearch.prepare("")
            val context = currentCoroutineContext()
            return repository.allEntries().sortedWith(compareByDescending<Entry> { it.timestamp }
                .thenByDescending { it.id }).asSequence().filter {
                context.ensureActive()
                filters.matches(it, zone)
            }.take(100).map { SearchResult(it, preview.fragment(it.text)) }.toList()
        }
        if (!byMeaning) return repository.searchWords(query, filters)
        val semantic = try { semanticSearch(query, filters) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        return semantic ?: repository.searchWords(query, filters)
    }
}
