package ru.privatediary.ml.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.privatediary.domain.EmbeddingStore
import ru.privatediary.domain.EntryRepository
import ru.privatediary.domain.models.EmbeddingChunk
import ru.privatediary.domain.models.SearchResult
import ru.privatediary.ml.embedder.EmbeddingEngine
import ru.privatediary.ml.embedder.EngineAvailability
import ru.privatediary.ml.search.EmbeddingCandidate
import ru.privatediary.ml.search.CosineRanking
import java.security.MessageDigest

/** Serial CPU work; cancellation is checked between chunks, writes use a text-hash guard. */
class SemanticSearchService(
    private val engine: EmbeddingEngine,
    private val entries: EntryRepository,
    private val vectors: EmbeddingStore,
) {
    private val mutex = Mutex()
    private val _available = MutableStateFlow(false)
    val available = _available.asStateFlow()
    private val _indexing = MutableStateFlow(false)
    val indexing = _indexing.asStateFlow()
    private val _revision = MutableStateFlow(0L)
    val revision = _revision.asStateFlow()

    suspend fun indexPending() = withContext(Dispatchers.Default) {
        mutex.withLock {
            try {
                _indexing.value = true
                _available.value = engine.prepare() == EngineAvailability.READY
                if (!_available.value) return@withLock
                for (entry in vectors.pendingIndexEntries()) {
                    currentCoroutineContext().ensureActive()
                    val hash = MessageDigest.getInstance("SHA-256").digest(entry.text.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    val chunks = engine.chunk(entry.text)
                    val embedded = ArrayList<EmbeddingChunk>()
                    for ((ordinal, chunk) in chunks.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        val vector = engine.embedPassage(chunk.text) ?: run {
                            _available.value = false
                            return@withLock
                        }
                        embedded += EmbeddingChunk(entry.id, ordinal, chunk.text, vector, hash)
                    }
                    if (embedded.isNotEmpty()) vectors.saveEmbeddings(entry.id, hash, embedded)
                }
                _revision.value++
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _available.value = false
            } finally { _indexing.value = false }
        }
    }

    suspend fun search(query: String): List<SearchResult>? = withContext(Dispatchers.Default) {
        if (engine.prepare() != EngineAvailability.READY) {
            _available.value = false
            return@withContext null
        }
        _available.value = true
        val queryVector = engine.embedQuery(query) ?: run {
            _available.value = false
            return@withContext null
        }
        val currentEntries = entries.allEntries().associateBy { it.id }
        val currentHashes = currentEntries.mapValues { (_, entry) ->
            MessageDigest.getInstance("SHA-256").digest(entry.text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
        val chunks = vectors.allEmbeddings().filter { chunk ->
            currentHashes[chunk.entryId] == chunk.contentHash
        }.map { EmbeddingCandidate(it.entryId, it.fragment, it.vector) }
        CosineRanking.rank(queryVector, chunks).mapNotNull { hit ->
            currentEntries[hit.entryId]?.let { SearchResult(it, hit.fragment, hit.similarity) }
        }
    }
}
