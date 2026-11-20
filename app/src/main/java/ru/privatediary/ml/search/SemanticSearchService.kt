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
import java.security.MessageDigest

/** Serial CPU work; cancellation is checked between chunks, writes use a text-hash guard. */
class SemanticSearchService(
    private val engine: EmbeddingEngine,
    private val entries: EntryRepository,
    private val vectors: EmbeddingStore,
    private val relevance: RelevanceEngine,
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
                val embeddingReady = engine.prepare() == EngineAvailability.READY
                if (!embeddingReady) {
                    _available.value = false
                    return@withLock
                }
                // Indexing can still make progress when the optional gate is unavailable,
                // but the UI must not describe unfiltered embedding search as ready.
                val relevanceReady = try { relevance.prepare() == EngineAvailability.READY }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
                catch (_: LinkageError) { false }
                catch (_: OutOfMemoryError) { false }
                _available.value = relevanceReady
                for (entry in vectors.pendingIndexEntries()) {
                    currentCoroutineContext().ensureActive()
                    val hash = textHash(entry.text)
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
        try {
            currentCoroutineContext().ensureActive()
            if (engine.prepare() != EngineAvailability.READY || relevance.prepare() != EngineAvailability.READY) {
                _available.value = false
                return@withContext null
            }
            val queryVector = engine.embedQuery(query) ?: run {
                _available.value = false
                return@withContext null
            }
            require(queryVector.isNotEmpty() && queryVector.all(Float::isFinite))
            val currentHashes = entries.allEntries().associate { it.id to textHash(it.text) }
            val order = compareByDescending<CheckedCandidate> { it.similarity }
                .thenBy { it.chunk.entryId }.thenBy { it.chunk.ordinal }
            val candidates = vectors.allEmbeddings().asSequence()
                .filter { currentHashes[it.entryId] == it.contentHash }
                .filter { it.vector.size == queryVector.size && it.vector.all(Float::isFinite) }
                .map { CheckedCandidate(it, VectorMath.cosine(queryVector, it.vector)) }
                .groupBy { it.chunk.entryId }.values
                .flatMap { chunks -> chunks.sortedWith(order).take(MAX_CHUNKS_PER_ENTRY) }
                .sortedWith(order).take(MAX_PAIRS)

            val accepted = mutableListOf<CheckedCandidate>()
            val acceptedEntries = mutableSetOf<Long>()
            for (candidate in candidates) {
                currentCoroutineContext().ensureActive()
                // Candidates are visited from best cosine to worst. Once one passes, every
                // remaining chunk of that entry is worse and cannot improve its result.
                if (candidate.chunk.entryId in acceptedEntries) continue
                val relevant = relevance.isRelevant(query, candidate.chunk.fragment, candidate.chunk.vector)
                currentCoroutineContext().ensureActive()
                if (relevant == null) {
                    _available.value = false
                    return@withContext null // Never expose a partially filtered list on failure.
                }
                if (relevant) {
                    accepted += candidate
                    acceptedEntries += candidate.chunk.entryId
                }
            }

            // A diary edit/delete can happen during native inference. Re-read content before
            // publishing so an old accepted fragment cannot survive a concurrent mutation.
            val freshEntries = entries.allEntries().associateBy { it.id }
            val freshHashes = freshEntries.mapValues { (_, entry) -> textHash(entry.text) }
            currentCoroutineContext().ensureActive()
            val results = accepted.filter { freshHashes[it.chunk.entryId] == it.chunk.contentHash }
                .groupBy { it.chunk.entryId }.values.map { it.minWith(order) }
                .sortedWith(order).take(MAX_RESULTS).mapNotNull { candidate ->
                    freshEntries[candidate.chunk.entryId]?.let { entry ->
                        SearchResult(entry, candidate.chunk.fragment, candidate.similarity)
                    }
                }
            _available.value = true
            results // A valid rejection of every candidate is [], not an FTS fallback.
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _available.value = false
            null
        } catch (_: LinkageError) {
            _available.value = false
            null
        } catch (_: OutOfMemoryError) {
            _available.value = false
            null
        }
    }

    private data class CheckedCandidate(val chunk: EmbeddingChunk, val similarity: Float)

    private companion object {
        const val MAX_CHUNKS_PER_ENTRY = 3
        const val MAX_PAIRS = 60
        const val MAX_RESULTS = 30

        fun textHash(text: String): String = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
