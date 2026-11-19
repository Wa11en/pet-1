package ru.privatediary.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.privatediary.ml.embedder.E5OnnxEncoder
import ru.privatediary.ml.search.CosineRanking
import ru.privatediary.ml.search.EmbeddingCandidate
import ru.privatediary.ml.search.VectorMath
import ru.privatediary.ml.search.ClosestResults
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.SearchResult
import ru.privatediary.ml.tokenizer.EntryChunker
import ru.privatediary.ml.tokenizer.SentencePieceTokenizer
import java.io.File
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

            // User regression: a high background cosine must not make food and gaming look
            // equally relevant. Exercise the real encoder, ranker and first-page selection.
            val diary = listOf("выиграл в доту", "поел пиццу").mapIndexed { i, text ->
                EmbeddingCandidate(i + 10L, text, encoder.embedPassage(text))
            }
            val food = CosineRanking.rank(encoder.embedQuery("еда"), diary)
            assertEquals("Food must rank pizza ahead of Dota", 11L, food.first().entryId)
            val foodResults = food.map { SearchResult(Entry(it.entryId, 0L, it.fragment), it.fragment, it.similarity) }
            val closest = ClosestResults.select(foodResults)
            assertEquals(listOf(11L), closest.primary.map { it.entry.id })
            assertEquals("The lower-ranked game remains available on request", listOf(10L), closest.additional.map { it.entry.id })
            println("Food regression: ${food.joinToString { "${it.fragment}: ${it.similarity}" }}")

            // Exercise inference on the same overlapping 200-token chunks used by the app.
            val longText = (passages[0].second + " ").repeat(25)
            val chunks = EntryChunker(tokenizer).chunk(longText)
            assertTrue(chunks.size > 1)
            assertTrue(chunks.all { it.tokenCount <= 200 })
            assertEquals(384, encoder.embedPassage(chunks.first().text).size)
        }
    }
}
