package ru.privatediary.ml.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.privatediary.ml.embedder.EmbeddingEngine
import ru.privatediary.ml.embedder.EngineAvailability

/**
 * Conservative compatibility check against fixed bilingual broad domains. This is not an
 * open-world relevance classifier: uncertain queries and conflicting domains are rejected.
 * Its decision is independent of which other entries happen to exist in the diary.
 */
class TopicRelevanceEngine(
    private val engine: EmbeddingEngine,
    private val cache: TopicPrototypeCache? = null,
) : RelevanceEngine {
    private val mutex = Mutex()
    private var prototypes: List<FloatArray>? = null
    private var memoizedQuery: String? = null
    private var memoizedTopic: Int? = null

    @Volatile override var availability = EngineAvailability.MODEL_MISSING
        private set

    override suspend fun prepare(): EngineAvailability = withContext(Dispatchers.Default) {
        mutex.withLock {
            try { prepareLocked() }
            catch (cancelled: CancellationException) { disable(); throw cancelled }
            catch (_: Exception) { disable() }
            catch (_: LinkageError) { disable() }
            catch (_: OutOfMemoryError) { disable() }
        }
    }

    override suspend fun isRelevant(query: String, fragment: String): Boolean? =
        check(query, fragment, null)

    override suspend fun isRelevant(query: String, fragment: String, embedding: FloatArray): Boolean? =
        check(query, fragment, embedding)

    private suspend fun check(query: String, fragment: String, suppliedEmbedding: FloatArray?): Boolean? =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                try {
                    currentCoroutineContext().ensureActive()
                    if (query.isBlank() || fragment.isBlank()) return@withLock false
                    if (prepareLocked() != EngineAvailability.READY) return@withLock null
                    val queryTopic = selectQueryTopic(query) ?: return@withLock false
                    val passage = suppliedEmbedding ?: engine.embedPassage(fragment)
                        ?: error("Passage embedding unavailable")
                    currentCoroutineContext().ensureActive()
                    require(validVector(passage)) { "Invalid passage vector" }
                    val scores = scores(passage, TopicCatalog.languageOf(fragment), TopicCatalog.Prefix.QUERY)
                    val best = scores.maxOrNull() ?: return@withLock false
                    best - scores[queryTopic] <= ENTRY_TOPIC_WINDOW + FLOAT_TOLERANCE
                } catch (cancelled: CancellationException) {
                    disable()
                    throw cancelled
                } catch (_: Exception) {
                    disable()
                    null
                } catch (_: LinkageError) {
                    disable()
                    null
                } catch (_: OutOfMemoryError) {
                    disable()
                    null
                }
            }
        }

    /** Caller holds [mutex]; do not call the public locking prepare method from here. */
    private suspend fun prepareLocked(): EngineAvailability {
        currentCoroutineContext().ensureActive()
        val state = engine.prepare()
        if (state != EngineAvailability.READY) return disable(state)
        if (prototypes != null) {
            availability = EngineAvailability.READY
            return availability
        }

        val cached = try { cache?.load()?.let { TopicPrototypeCodec.decode(TopicCatalog.SHA256, it) } }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        if (cached != null) {
            prototypes = cached
            availability = EngineAvailability.READY
            return availability
        }

        val generated = ArrayList<FloatArray>(TopicCatalog.prototypes.size)
        for (prototype in TopicCatalog.prototypes) {
            currentCoroutineContext().ensureActive()
            val vector = when (prototype.prefix) {
                TopicCatalog.Prefix.QUERY -> engine.embedQuery(prototype.text)
                TopicCatalog.Prefix.PASSAGE -> engine.embedPassage(prototype.text)
            } ?: error("Topic prototype embedding unavailable")
            currentCoroutineContext().ensureActive()
            require(validVector(vector)) { "Invalid topic prototype vector" }
            generated += vector.copyOf()
        }
        // Commit only a complete set. Cancellation/error never exposes partial prototypes.
        currentCoroutineContext().ensureActive()
        prototypes = generated
        availability = EngineAvailability.READY
        try { cache?.save(TopicPrototypeCodec.encode(TopicCatalog.SHA256, generated)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A read-only/full filesystem must not disable valid RAM data. */ }
        return availability
    }

    private suspend fun selectQueryTopic(query: String): Int? {
        if (memoizedQuery == query) return memoizedTopic
        val vector = engine.embedQuery(query) ?: error("Query embedding unavailable")
        currentCoroutineContext().ensureActive()
        require(validVector(vector)) { "Invalid query vector" }
        val scores = scores(vector, TopicCatalog.languageOf(query), TopicCatalog.Prefix.PASSAGE)
        val ordered = scores.indices.sortedWith(compareByDescending<Int> { scores[it] }.thenBy { it })
        val best = ordered[0]
        val selected = if (scores[best] - scores[ordered[1]] + FLOAT_TOLERANCE >= QUERY_TOPIC_GAP) best else null
        memoizedQuery = query
        memoizedTopic = selected
        return selected
    }

    private fun scores(vector: FloatArray, language: TopicCatalog.Language, prefix: TopicCatalog.Prefix): FloatArray {
        val vectors = checkNotNull(prototypes)
        val scores = FloatArray(TopicCatalog.topics.size) { Float.NEGATIVE_INFINITY }
        TopicCatalog.prototypes.forEachIndexed { index, prototype ->
            if (prototype.language == language && prototype.prefix == prefix) {
                scores[prototype.topicIndex] = maxOf(scores[prototype.topicIndex], VectorMath.cosine(vector, vectors[index]))
            }
        }
        return scores
    }

    private fun disable(state: EngineAvailability = EngineAvailability.UNAVAILABLE): EngineAvailability {
        availability = state
        memoizedQuery = null
        memoizedTopic = null
        return state
    }

    private companion object {
        const val QUERY_TOPIC_GAP = 0.015f
        const val ENTRY_TOPIC_WINDOW = 0.01f
        const val FLOAT_TOLERANCE = 0.000001f

        fun validVector(vector: FloatArray): Boolean = vector.size == TopicPrototypeCodec.DIMENSIONS &&
            vector.all(Float::isFinite) && vector.sumOf { it.toDouble() * it } in 0.99..1.01
    }
}
