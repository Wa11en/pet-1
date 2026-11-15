package ru.privatediary.ml

import ru.privatediary.ml.tokenizer.TextTokenizer
import ru.privatediary.ml.tokenizer.TokenPiece
import ru.privatediary.ml.tokenizer.EntryChunker

import org.junit.Assert.*
import org.junit.Test

class ChunkingTest {
    private val tokenizer = object : TextTokenizer {
        override fun tokenize(text: String): List<TokenPiece> {
            val result = mutableListOf<TokenPiece>()
            var i = 0
            while (i < text.length) {
                val end = i + Character.charCount(text.codePointAt(i))
                result += TokenPiece(4, i, end)
                i = end
            }
            return result
        }
    }

    @Test fun windowsHaveTokenOverlapAndCoverOriginalText() {
        val text = "ж".repeat(501)
        val chunks = EntryChunker(tokenizer).chunk(text)
        assertEquals(listOf(0, 176, 352), chunks.map { it.start })
        assertEquals(listOf(200, 376, 501), chunks.map { it.end })
        assertEquals(listOf(200, 200, 149), chunks.map { it.tokenCount })
        chunks.forEach { assertEquals(text.substring(it.start, it.end), it.text) }
        assertEquals(24, chunks[0].end - chunks[1].start)
    }

    @Test fun supplementaryCharactersNeverSplitAndFinalFragmentIsRetained() {
        val text = "🧘".repeat(201)
        val chunks = EntryChunker(tokenizer).chunk(text)
        assertEquals(2, chunks.size)
        assertEquals(text.length, chunks.last().end)
        chunks.forEach { assertEquals(0, it.text.length % 2) }
    }

    @Test fun emptyInputHasNoChunksAndShortInputIsPreserved() {
        assertTrue(EntryChunker(tokenizer).chunk("").isEmpty())
        assertEquals("  Привет!  ", EntryChunker(tokenizer).chunk("  Привет!  ").single().text)
    }

    @Test(expected = IllegalArgumentException::class)
    fun overlapMustAllowForwardProgress() { EntryChunker(tokenizer, 24, 24) }
}
