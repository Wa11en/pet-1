package ru.privatediary.domain

import kotlinx.coroutines.flow.Flow
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.SearchResult
import ru.privatediary.domain.models.SearchFilters

interface EntryRepository {
    fun observeEntries(): Flow<List<Entry>>
    suspend fun get(id: Long): Entry?
    suspend fun upsert(entry: Entry): Long
    suspend fun importEntries(entries: List<Entry>)
    suspend fun delete(id: Long)
    suspend fun searchWords(query: String, filters: SearchFilters = SearchFilters()): List<SearchResult>
    suspend fun allEntries(): List<Entry>
    /** Atomic replace used only after backup authentication and validation. */
    suspend fun replaceAll(entries: List<Entry>)
}
