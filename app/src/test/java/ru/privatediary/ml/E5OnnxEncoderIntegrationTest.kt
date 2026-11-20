package ru.privatediary.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.privatediary.ml.embedder.E5OnnxEncoder
import ru.privatediary.ml.search.CosineRanking
import ru.privatediary.ml.search.EmbeddingCandidate
import ru.privatediary.ml.search.VectorMath
import ru.privatediary.ml.search.TopicRelevanceEngine
import ru.privatediary.ml.search.SemanticSearchService
import ru.privatediary.ml.embedder.EmbeddingEngine
import ru.privatediary.ml.embedder.EngineAvailability
import ru.privatediary.domain.models.EmbeddingChunk
import ru.privatediary.domain.usecases.SearchEntries
import ru.privatediary.testing.SearchFakeRepository
import ru.privatediary.testing.SearchFakeVectors
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.SearchResult
import ru.privatediary.ml.tokenizer.EntryChunker
import ru.privatediary.ml.tokenizer.SentencePieceTokenizer
import java.io.File
import java.security.MessageDigest
import kotlin.math.abs

/**
 * An opt-in test with the real, pinned model. It never downloads anything. Run with desktop
 * ONNX Runtime and -DofflineModelDirectory=.../assets/model (Android's JNI is not a Linux JVM).
 */
class E5OnnxEncoderIntegrationTest {
    @Test fun realQuantizedModelFindsRussianEnglishAndCrossLanguageMeaningWithoutQueryWords() {
        val directory = System.getProperty("offlineModelDirectory")?.let(::File)
        assumeTrue("Real-model verification is opt-in", directory?.isDirectory == true)
        val model = File(directory!!, "model_int8.onnx")
        val vocabulary = File(directory, "tokenizer.json")
        assertTrue("The bundled quantized model must exist", model.isFile)
        assertTrue("The bundled vocabulary must exist", vocabulary.isFile)
        val started = System.nanoTime()
        val tokenizer = vocabulary.bufferedReader().use { SentencePieceTokenizer(it) }
        val runtime = Runtime.getRuntime()
        println("Tokenizer load: ${(System.nanoTime() - started) / 1_000_000} ms; Java heap in use: ${(runtime.totalMemory() - runtime.freeMemory()) / 1_048_576} MiB")
        val passages = listOf(
            1L to "Вечером я сидел на берегу озера, смотрел на воду и слушал птиц. Мысли перестали метаться, дыхание стало ровным, я никуда не торопился.",
            2L to "На работе сорвали сроки. Я весь день нервничал из-за дедлайна, сердце колотилось, а ночью не мог уснуть.",
            3L to "I sat by the lake at sunset, listened to birds and watched the water. My breathing slowed down, my mind stopped racing and I had nowhere to rush.",
            4L to "The deadline was impossible. I worried about the presentation all day, my heart was racing and I could not sleep.",
            5L to "Купил в магазине картофель, молоко и хлеб. Потом оплатил счета за электричество.",
        )
        assertTrue(passages.none { (_, text) -> text.contains("спокой", ignoreCase = true) || text.contains("calm", ignoreCase = true) })

        E5OnnxEncoder(model, tokenizer).use { encoder ->
            val candidates = passages.map { (id, text) ->
                val embedding = encoder.embedPassage(text)
                assertEquals(384, embedding.size)
                assertTrue(embedding.all(Float::isFinite))
                assertTrue(abs(VectorMath.cosine(embedding, embedding) - 1f) < 0.00001f)
                assertTrue(abs(embedding.sumOf { it.toDouble() * it } - 1.0) < 0.0001)
                EmbeddingCandidate(id, text, embedding)
            }
            val queries = listOf("Когда я чувствовал спокойствие?", "When did I feel calm?")
            queries.forEach { query ->
                val vector = encoder.embedQuery(query)
                val hits = CosineRanking.rank(vector, candidates)
                println("$query -> ${hits.joinToString { "${it.entryId}: ${"%.4f".format(it.similarity)}" }}")
                val expected = if (query == queries[0]) 1L else 3L
                assertEquals("Expected the lake entry for '$query'", expected, hits.first().entryId)
                val scores = hits.associate { it.entryId to it.similarity }
                assertTrue("The best lake entry must outrank unrelated shopping", hits.first().similarity > scores.getValue(5L))
            }
            val russianQuery = encoder.embedQuery(queries[0])
            val englishOnly = CosineRanking.rank(russianQuery, candidates.filter { it.entryId in setOf(3L, 4L) })
            assertEquals("Russian query should find the English meaning", 3L, englishOnly.first().entryId)

            // Exercise raw ranking separately from the actual relevance decision.
            val diary = listOf("выиграл в доту", "поел пиццу").mapIndexed { i, text ->
                EmbeddingCandidate(i + 10L, text, encoder.embedPassage(text))
            }
            val food = CosineRanking.rank(encoder.embedQuery("еда"), diary)
            assertEquals("Food must rank pizza ahead of Dota", 11L, food.first().entryId)
            println("Food regression: ${food.joinToString { "${it.fragment}: ${it.similarity}" }}")

            val engine = object : EmbeddingEngine {
                override val availability = EngineAvailability.READY
                override suspend fun prepare() = availability
                override suspend fun embedQuery(text: String) = encoder.embedQuery(text)
                override suspend fun embedPassage(text: String) = encoder.embedPassage(text)
                override suspend fun chunk(text: String) = EntryChunker(tokenizer).chunk(text)
            }
            runBlocking {
                val relevance = TopicRelevanceEngine(engine)
                assertEquals(EngineAvailability.READY, relevance.prepare())
                val rows = listOf(Entry(10, 0, "выиграл в доту"), Entry(11, 0, "поел пиццу"))
                val repository = SearchFakeRepository(rows)
                val vectors = SearchFakeVectors()
                fun vectorRow(entry: Entry) = EmbeddingChunk(entry.id, 0, entry.text, encoder.embedPassage(entry.text),
                    MessageDigest.getInstance("SHA-256").digest(entry.text.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) })
                val stored = rows.map(::vectorRow)
                vectors.stored = stored
                val service = SemanticSearchService(engine, repository, vectors, relevance)
                val search = SearchEntries(repository, service::search)
                assertEquals("Food must actually exclude the game", listOf(11L), search("еда", true).map { it.entry.id })
                assertEquals("Games must actually exclude pizza", listOf(10L), search("игры", true).map { it.entry.id })

                repository.rows = listOf(rows[0])
                vectors.stored = listOf(stored[0])
                assertTrue("A lone game must not be shown for food", search("еда", true).isEmpty())
                assertTrue("A valid rejection must never fall through to words", repository.wordQueries.isEmpty())
                assertTrue(service.available.value)

                val games = listOf(Entry(20, 0, "поиграл в контру"), Entry(21, 0, "выиграл в доту"),
                    Entry(22, 0, "поел пиццу"))
                repository.rows = games
                vectors.stored = games.map(::vectorRow)
                assertEquals("The reported word fragment must work in meaning mode too", setOf(20L, 21L),
                    search("игра", true).map { it.entry.id }.toSet())
                assertTrue("Inflected game names do not need a catalog entry",
                    search("контра", true).any { it.entry.id == 20L })
                assertTrue(search("дота", true).any { it.entry.id == 21L })

                // Real open-vocabulary meanings: these have no matching lexical root and
                // could not pass the former mandatory topic-selection rule.
                val openCases = listOf(
                    Triple("кот", "Сегодня гладил пушистую кошку, она мурлыкала.", true),
                    Triple("кот", "Выиграл в доту.", false),
                    Triple("cats", "I stroked my kitten while it purred.", true),
                    Triple("cats", "I won at Dota.", false),
                    Triple("coding", "I fixed a bug in my Kotlin program.", true),
                    Triple("coding", "I ate pizza for lunch.", false),
                    Triple("программирование", "Написал программу на Kotlin, исправил ошибку в коде.", true),
                    Triple("программирование", "Готовил суп и ел хлеб.", false),
                    Triple("дождь", "С неба лило весь вечер, я промок насквозь.", true),
                    Triple("дождь", "Прошел новый уровень в игре.", false),
                    Triple("еда", "Вчера была победа в Доте.", false),
                    Triple("кот", "Некоторые страницы были пустыми.", false),
                    Triple("cat", "I read an article about education.", false),
                    Triple("cat", "I booked a catering service for dinner.", false),
                )
                for ((query, text, expected) in openCases) {
                    assertEquals("Open query '$query' / '$text'", expected, relevance.isRelevant(query, text))
                }
                println("Open queries: ${openCases.size} actual-model cases passed; game fragments returned both reported entries")

                // Frozen, independently labelled RU/EN corpus. No parameter tuning in this test.
                val fixture = javaClass.getResourceAsStream("/semantic_relevance_cases.json")
                    ?: error("Missing semantic relevance fixture")
                val cases = fixture.bufferedReader().use { JSONArray(it.readText()) }
                var acceptedRelevant = 0
                var rejectedUnrelated = 0
                for (i in 0 until cases.length()) {
                    val case = cases.getJSONObject(i)
                    val query = case.getString("query")
                    val text = case.getString("passage")
                    val accepted = relevance.isRelevant(query, text) ?: error("The real relevance engine became unavailable")
                    if (case.getBoolean("relevant")) {
                        if (accepted) acceptedRelevant++
                    } else {
                        assertFalse("Off-topic fixture admitted: '$query' / '$text'", accepted)
                        rejectedUnrelated++
                    }
                }
                assertEquals(20, rejectedUnrelated)
                assertTrue("At least 16/20 relevant fixtures must survive the strict gate", acceptedRelevant >= 16)
                println("Relevance corpus: $acceptedRelevant/20 relevant retained; $rejectedUnrelated/20 unrelated rejected")
            }

            // Exercise inference on the same overlapping 200-token chunks used by the app.
            val longText = (passages[0].second + " ").repeat(25)
            val chunks = EntryChunker(tokenizer).chunk(longText)
            assertTrue(chunks.size > 1)
            assertTrue(chunks.all { it.tokenCount <= 200 })
            assertEquals(384, encoder.embedPassage(chunks.first().text).size)
        }
    }
}
