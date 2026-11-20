package ru.privatediary.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import ru.privatediary.domain.EmbeddingStore
import ru.privatediary.domain.EntryRepository
import ru.privatediary.domain.models.EmbeddingChunk
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.SearchResult
import ru.privatediary.domain.models.SearchFilters
import ru.privatediary.ml.embedder.EmbeddingEngine
import ru.privatediary.ml.embedder.EngineAvailability
import ru.privatediary.ml.tokenizer.TextChunk
import ru.privatediary.ml.search.RelevanceEngine

class SearchFakeRepository(var rows: List<Entry> = emptyList()) : EntryRepository {
    var wordQueries = mutableListOf<String>()
    var wordFilters = mutableListOf<SearchFilters>()
    var wordResults = emptyList<SearchResult>()
    var allEntriesCalls = 0
    override fun observeEntries(): Flow<List<Entry>> = flowOf(rows)
    override suspend fun get(id: Long): Entry? = rows.find { it.id == id }
    override suspend fun searchWords(query: String, filters: SearchFilters): List<SearchResult> {
        wordQueries += query
        wordFilters += filters
        return wordResults.filter { filters.matches(it.entry) }
    }
    override suspend fun allEntries(): List<Entry> { allEntriesCalls++; return rows }
    override suspend fun upsert(entry: Entry): Long = error("Unexpected write")
    override suspend fun importEntries(entries: List<Entry>): Unit = error("Unexpected write")
    override suspend fun delete(id: Long): Unit = error("Unexpected write")
    override suspend fun replaceAll(entries: List<Entry>): Unit = error("Unexpected write")
}

class SearchFakeEngine : EmbeddingEngine {
    override var availability = EngineAvailability.READY
    var prepareCalls = 0
    var queryCalls = 0
    var passages = mutableListOf<String>()
    var chunks = listOf(TextChunk("old entry", 0, 9, 2))
    var passageResult: suspend (String) -> FloatArray? = { floatArrayOf(1f, 0f) }
    var queryResult: suspend (String) -> FloatArray? = { floatArrayOf(1f, 0f) }
    override suspend fun prepare(): EngineAvailability { prepareCalls++; return availability }
    override suspend fun embedPassage(text: String): FloatArray? { passages += text; return passageResult(text) }
    override suspend fun embedQuery(text: String): FloatArray? { queryCalls++; return queryResult(text) }
    override suspend fun chunk(text: String): List<TextChunk> = chunks
}

class SearchFakeVectors : EmbeddingStore {
    data class Write(val entryId: Long, val expectedHash: String, val chunks: List<EmbeddingChunk>)
    var pending = emptyList<Entry>()
    var stored = emptyList<EmbeddingChunk>()
    var writes = mutableListOf<Write>()
    var acceptWrites = true
    var allEmbeddingsCalls = 0
    override suspend fun pendingIndexEntries(): List<Entry> = pending
    override suspend fun allEmbeddings(): List<EmbeddingChunk> { allEmbeddingsCalls++; return stored }
    override suspend fun saveEmbeddings(entryId: Long, expectedTextHash: String, chunks: List<EmbeddingChunk>): Boolean {
        writes += Write(entryId, expectedTextHash, chunks)
        if (acceptWrites) stored = chunks
        return acceptWrites
    }
}

class SearchFakeRelevance : RelevanceEngine {
    override var availability = EngineAvailability.READY
    var prepareCalls = 0
    val pairs = mutableListOf<Pair<String, String>>()
    var result: suspend (String, String) -> Boolean? = { _, _ -> true }
    override suspend fun prepare(): EngineAvailability { prepareCalls++; return availability }
    override suspend fun isRelevant(query: String, fragment: String): Boolean? {
        pairs += query to fragment
        return try {
            result(query, fragment).also { if (it == null) availability = EngineAvailability.UNAVAILABLE }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            availability = EngineAvailability.UNAVAILABLE
            throw error
        }
    }
}
