package ru.privatediary.ml

/** Offsets address the original String in UTF-16, as used by Compose text selections. */
data class TokenPiece(val id: Int, val start: Int, val end: Int)

interface TextTokenizer {
    /** Content tokens only: BOS/EOS are added by [encode]. */
    fun tokenize(text: String): List<TokenPiece>

    fun encode(text: String, maxTokens: Int = 512): LongArray {
        require(maxTokens >= 2)
        val tokens = tokenize(text).take(maxTokens - 2)
        return longArrayOf(0) + tokens.map { it.id.toLong() }.toLongArray() + longArrayOf(2)
    }
}

data class TextChunk(val text: String, val start: Int, val end: Int, val tokenCount: Int)

class EntryChunker(
    private val tokenizer: TextTokenizer,
    private val maxTokens: Int = 200,
    private val overlapTokens: Int = 24,
) {
    init {
        require(maxTokens > 0)
        require(overlapTokens in 0 until maxTokens)
    }

    /** Windows advance in token space; fragments always retain their original source text. */
    fun chunk(text: String): List<TextChunk> {
        val tokens = tokenizer.tokenize(text)
        if (tokens.isEmpty()) return emptyList()
        val result = mutableListOf<TextChunk>()
        var first = 0
        while (first < tokens.size) {
            val lastExclusive = minOf(tokens.size, first + maxTokens)
            val start = if (first == 0) 0 else tokens[first].start
            val end = if (lastExclusive == tokens.size) text.length else tokens[lastExclusive - 1].end
            if (end > start) result += TextChunk(text.substring(start, end), start, end, lastExclusive - first)
            if (lastExclusive == tokens.size) break
            first = lastExclusive - overlapTokens
        }
        return result
    }
}
