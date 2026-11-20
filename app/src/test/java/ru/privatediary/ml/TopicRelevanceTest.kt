package ru.privatediary.ml

import java.io.IOException
import kotlin.math.sqrt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import ru.privatediary.ml.embedder.EmbeddingEngine
import ru.privatediary.ml.embedder.EngineAvailability
import ru.privatediary.ml.search.TopicCatalog
import ru.privatediary.ml.search.TopicPrototypeCache
import ru.privatediary.ml.search.TopicPrototypeCodec
import ru.privatediary.ml.search.TopicRelevanceEngine
import ru.privatediary.ml.tokenizer.TextChunk

class TopicRelevanceTest {
    @Test fun incompatibleDomainIsRejectedEvenAsTheOnlyCandidateAndAcrossLanguages() = runBlocking {
        val model = PrototypeModel()
        val filter = TopicRelevanceEngine(model)
        assertEquals(true, filter.isRelevant("еда", "Пицца"))
        assertEquals(false, filter.isRelevant("еда", "Игра"))
        assertEquals(true, filter.isRelevant("еда", "pizza"))
        assertEquals(false, filter.isRelevant("еда", "game"))
        assertEquals(true, filter.isRelevant("food", "Пицца"))
        assertEquals(EngineAvailability.READY, filter.availability)
    }

    @Test fun tiedOrUncertainQueryRejectsWithoutPretendingTheModelFailed() = runBlocking {
        val model = PrototypeModel()
        model.queryVectors["ambiguous"] = blend(foodEnglish = 0.5f, gamesEnglish = 0.5f)
        val filter = TopicRelevanceEngine(model)
        assertEquals(false, filter.isRelevant("ambiguous", "pizza"))
        assertEquals(EngineAvailability.READY, filter.availability)
        // An uncertain query is a valid empty decision and must not trigger raw-result fallback.
        assertEquals(false, filter.isRelevant("ambiguous", "game"))
    }

    @Test fun queryAndEntryBoundariesIncludeTheDeclaredWindowWithoutRescaling() = runBlocking {
        val model = PrototypeModel()
        model.queryVectors["boundary"] = blend(foodEnglish = 0.5f, gamesEnglish = 0.485f)
        model.queryVectors["below"] = blend(foodEnglish = 0.5f, gamesEnglish = 0.486f)
        model.passageVectors["entry boundary"] = blend(foodEnglish = 0.49f, gamesEnglish = 0.5f)
        model.passageVectors["entry outside"] = blend(foodEnglish = 0.488f, gamesEnglish = 0.5f)
        val filter = TopicRelevanceEngine(model)
        assertEquals(true, filter.isRelevant("boundary", "entry boundary"))
        assertEquals(false, filter.isRelevant("below", "entry boundary"))
        assertEquals(false, filter.isRelevant("boundary", "entry outside"))
    }

    @Test fun storedCandidateVectorIsReusedAndNotModifiedWhileOnlyOneQueryIsMemoized() = runBlocking {
        val model = PrototypeModel()
        val filter = TopicRelevanceEngine(model)
        filter.prepare()
        val initialPassages = model.passageCalls
        val initialQueries = model.queryCalls
        val stored = basis(FOOD_EN)
        val original = stored.copyOf()
        assertEquals(true, filter.isRelevant("food", "pizza", stored))
        assertEquals(false, filter.isRelevant("food", "game", basis(GAMES_EN)))
        assertEquals(initialPassages, model.passageCalls)
        assertEquals(initialQueries + 1, model.queryCalls)
        assertArrayEquals(original, stored, 0f)
        filter.isRelevant("еда", "Пицца", basis(FOOD_RU))
        filter.isRelevant("food", "pizza", stored)
        assertEquals(initialQueries + 3, model.queryCalls)
    }

    @Test fun validCacheAvoidsRegenerationButCannotReplaceAMissingModel() = runBlocking {
        val cache = MemoryCache()
        TopicRelevanceEngine(PrototypeModel(), cache).prepare()
        assertNotNull(cache.bytes)
        val secondModel = PrototypeModel()
        assertEquals(EngineAvailability.READY, TopicRelevanceEngine(secondModel, cache).prepare())
        assertEquals(0, secondModel.embeddings)

        val missing = PrototypeModel().apply { availability = EngineAvailability.MODEL_MISSING }
        val unavailable = TopicRelevanceEngine(missing, cache)
        assertEquals(EngineAvailability.MODEL_MISSING, unavailable.prepare())
        assertNull(unavailable.isRelevant("food", "pizza"))
        assertEquals(0, missing.embeddings)
    }

    @Test fun corruptCacheIsRegeneratedAndContainsOnlyPublicPrototypeVectors() = runBlocking {
        val cache = MemoryCache().apply { bytes = byteArrayOf(1, 2, 3) }
        val model = PrototypeModel()
        val filter = TopicRelevanceEngine(model, cache)
        assertEquals(EngineAvailability.READY, filter.prepare())
        assertEquals(TopicCatalog.prototypes.size, model.embeddings)
        assertNotNull(TopicPrototypeCodec.decode(TopicCatalog.SHA256, cache.bytes!!))
        val publicBytes = cache.bytes!!.copyOf()
        assertEquals(true, filter.isRelevant("food", "pizza"))
        assertEquals(1, cache.saves)
        assertArrayEquals(publicBytes, cache.bytes)
    }

    @Test fun failedCacheWritesDoNotDisablePreparedPrototypes() = runBlocking {
        val cache = object : TopicPrototypeCache {
            override suspend fun load(): ByteArray? = throw IOException("Unavailable cache")
            override suspend fun save(bytes: ByteArray): Unit = throw IOException("Disk full")
        }
        val filter = TopicRelevanceEngine(PrototypeModel(), cache)
        assertEquals(EngineAvailability.READY, filter.prepare())
        assertEquals(true, filter.isRelevant("food", "pizza"))
    }

    @Test fun cancellationNeverCommitsAPartialPrototypeSetAndCanRetry() = runBlocking {
        val cache = MemoryCache()
        val model = PrototypeModel().apply { cancelAt = 5 }
        val filter = TopicRelevanceEngine(model, cache)
        try {
            filter.prepare()
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertEquals(EngineAvailability.UNAVAILABLE, filter.availability)
            assertEquals(0, cache.saves)
        }
        model.cancelAt = null
        assertEquals(EngineAvailability.READY, filter.prepare())
        assertNotNull(TopicPrototypeCodec.decode(TopicCatalog.SHA256, cache.bytes!!))
        assertEquals(true, filter.isRelevant("food", "pizza"))
    }

    @Test fun inferenceFailureAndCorruptStoredVectorsRequestFallbackInsteadOfPassing() = runBlocking {
        val model = PrototypeModel()
        val filter = TopicRelevanceEngine(model)
        filter.prepare()
        model.failQuery = "food"
        assertNull(filter.isRelevant("food", "pizza"))
        assertEquals(EngineAvailability.UNAVAILABLE, filter.availability)
        model.failQuery = null
        assertNull(filter.isRelevant("food", "pizza", FloatArray(384)))
        assertEquals(EngineAvailability.UNAVAILABLE, filter.availability)
        assertEquals(true, filter.isRelevant("food", "pizza"))
    }

    private class MemoryCache : TopicPrototypeCache {
        var bytes: ByteArray? = null
        var saves = 0
        override suspend fun load() = bytes?.copyOf()
        override suspend fun save(bytes: ByteArray) { this.bytes = bytes.copyOf(); saves++ }
    }

    private class PrototypeModel : EmbeddingEngine {
        override var availability = EngineAvailability.READY
        var embeddings = 0
        var queryCalls = 0
        var passageCalls = 0
        var cancelAt: Int? = null
        var failQuery: String? = null
        val queryVectors = mutableMapOf("еда" to basis(FOOD_RU), "food" to basis(FOOD_EN))
        val passageVectors = mutableMapOf("Пицца" to basis(FOOD_RU), "Игра" to basis(GAMES_RU),
            "pizza" to basis(FOOD_EN), "game" to basis(GAMES_EN))
        private val descriptions = TopicCatalog.topics.flatMapIndexed { index, topic ->
            topic.russian.map { it to basis(index * 2) } + topic.english.map { it to basis(index * 2 + 1) }
        }.toMap()

        override suspend fun prepare() = availability
        override suspend fun embedQuery(text: String): FloatArray? {
            embedded(); queryCalls++
            return if (text == failQuery) null else queryVectors[text] ?: descriptions[text]
        }
        override suspend fun embedPassage(text: String): FloatArray? {
            embedded(); passageCalls++
            return passageVectors[text] ?: descriptions[text]
        }
        override suspend fun chunk(text: String) = emptyList<TextChunk>()
        private fun embedded() {
            embeddings++
            if (embeddings == cancelAt) throw CancellationException("Locked during preparation")
        }
    }

    private companion object {
        const val FOOD_RU = 0
        const val FOOD_EN = 1
        const val GAMES_RU = 2
        const val GAMES_EN = 3
        fun basis(index: Int) = FloatArray(384).also { it[index] = 1f }
        fun blend(foodEnglish: Float, gamesEnglish: Float): FloatArray = FloatArray(384).also {
            it[FOOD_EN] = foodEnglish
            it[GAMES_EN] = gamesEnglish
            it[100] = sqrt(1f - foodEnglish * foodEnglish - gamesEnglish * gamesEnglish)
        }
    }
}
