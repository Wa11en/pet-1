package ru.privatediary.domain.usecases

import kotlinx.coroutines.CancellationException
import ru.privatediary.domain.EntryRepository
import ru.privatediary.domain.models.SearchResult

/** Semantic implementation is injected; null means unavailable and enables the FTS fallback. */
class SearchEntries(
    private val repository: EntryRepository,
    private val semanticSearch: suspend (String) -> List<SearchResult>?,
) {
    suspend operator fun invoke(query: String, byMeaning: Boolean): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        if (!byMeaning) return repository.searchWords(query)
        val semantic = try { semanticSearch(query) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        return semantic ?: repository.searchWords(query)
    }
}
