package ru.privatediary.ml.tokenizer

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * XLM-R SentencePiece Unigram: model scores, Viterbi segmentation, model-provided
 * precompiled Unicode normalization and Metaspace. No JNI and no Android APIs.
 * The vocabulary in Hugging Face tokenizer.json already contains XLM-R token IDs.
 */
class SentencePieceTokenizer(json: String) : TextTokenizer {
    private data class Piece(val id: Int, val score: Double)
    private val vocabulary: Map<String, Piece>
    private val longestPiece: Int
    private val unknownId: Int
    private val unknownScore: Double
    private val normalizer: PrecompiledNormalizer?
    private val specialTokens = Regex("(?U)\\s*<mask>|<s>|</s>|<pad>|<unk>")

    init {
        val root = JSONObject(json)
        val model = root.getJSONObject("model")
        require(model.getString("type") == "Unigram") { "Expected XLM-R SentencePiece Unigram" }
        unknownId = model.getInt("unk_id")
        val vocab = model.getJSONArray("vocab")
        val pieces = HashMap<String, Piece>(vocab.length() * 4 / 3 + 1)
        var longest = 0
        var minimum = Double.POSITIVE_INFINITY
        for (i in 0 until vocab.length()) {
            val row = vocab.getJSONArray(i)
            val text = row.getString(0)
            val score = row.getDouble(1)
            require(text.isNotEmpty() && score.isFinite())
            pieces[text] = Piece(i, score)
            longest = maxOf(longest, text.length)
            minimum = minOf(minimum, score)
        }
        require(pieces["<s>"]?.id == 0 && pieces["</s>"]?.id == 2)
        require(unknownId in 0 until vocab.length())
        vocabulary = pieces
        longestPiece = longest
        unknownScore = minimum - 10.0
        val norms = root.optJSONObject("normalizer")?.optJSONArray("normalizers")
        var charsMap: PrecompiledNormalizer? = null
        if (norms != null) for (i in 0 until norms.length()) {
            val item = norms.getJSONObject(i)
            if (item.getString("type") == "Precompiled") {
                charsMap = PrecompiledNormalizer(Base64.getDecoder().decode(item.getString("precompiled_charsmap")))
            }
        }
        normalizer = charsMap
    }

    override fun tokenize(text: String): List<TokenPiece> {
        if (text.isEmpty()) return emptyList()
        val output = mutableListOf<TokenPiece>()
        var start = 0
        specialTokens.findAll(text).forEach { match ->
            if (match.range.first > start) output += tokenizePart(text.substring(start, match.range.first), start)
            val symbol = match.value.trimStart()
            val piece = vocabulary[symbol]
            if (piece != null) output += TokenPiece(piece.id, match.range.first, match.range.last + 1)
            else output += tokenizePart(match.value, match.range.first)
            start = match.range.last + 1
        }
        if (start < text.length) output += tokenizePart(text.substring(start), start)
        return output
    }

    private fun tokenizePart(original: String, sourceOffset: Int): List<TokenPiece> {
        val normalized = normalize(original)
        val text = normalized.text
        if (text.isEmpty()) return emptyList()
        val scores = DoubleArray(text.length + 1) { Double.NEGATIVE_INFINITY }
        val previous = IntArray(text.length + 1) { -1 }
        val ids = IntArray(text.length + 1) { unknownId }
        scores[0] = 0.0
        var begin = 0
        while (begin < text.length) {
            val scalarEnd = begin + Character.charCount(text.codePointAt(begin))
            if (scores[begin] != Double.NEGATIVE_INFINITY) {
                var singleKnown = false
                val limit = minOf(text.length, begin + longestPiece)
                var end = scalarEnd
                while (end <= limit) {
                    val piece = vocabulary[text.substring(begin, end)]
                    if (piece != null) {
                        if (end == scalarEnd) singleKnown = true
                        val score = scores[begin] + piece.score
                        if (score > scores[end]) {
                            scores[end] = score
                            previous[end] = begin
                            ids[end] = piece.id
                        }
                    }
                    if (end == text.length) break
                    end += Character.charCount(text.codePointAt(end))
                }
                if (!singleKnown && scores[begin] + unknownScore > scores[scalarEnd]) {
                    scores[scalarEnd] = scores[begin] + unknownScore
                    previous[scalarEnd] = begin
                    ids[scalarEnd] = unknownId
                }
            }
            begin = scalarEnd
        }
        val reversed = mutableListOf<TokenPiece>()
        var end = text.length
        while (end > 0) {
            val first = previous[end]
            check(first >= 0) { "Unreachable tokenizer state" }
            reversed += TokenPiece(ids[end], sourceOffset + normalized.starts[first], sourceOffset + normalized.ends[end - 1])
            end = first
        }
        val result = mutableListOf<TokenPiece>()
        reversed.asReversed().forEach { piece ->
            val last = result.lastOrNull()
            if (piece.id == unknownId && last?.id == unknownId) {
                result[result.lastIndex] = last.copy(end = piece.end)
            } else result += piece
        }
        return result
    }

    private data class Normalized(val text: String, val starts: IntArray, val ends: IntArray)

    private fun normalize(original: String): Normalized {
        val bytes = original.toByteArray(Charsets.UTF_8)
        val byteToSource = IntArray(bytes.size + 1)
        var byteIndex = 0
        var charIndex = 0
        while (charIndex < original.length) {
            val chars = Character.charCount(original.codePointAt(charIndex))
            val count = original.substring(charIndex, charIndex + chars).toByteArray(Charsets.UTF_8).size
            repeat(count) { byteToSource[byteIndex++] = charIndex }
            charIndex += chars
        }
        byteToSource[bytes.size] = original.length
        val text = StringBuilder()
        val starts = mutableListOf<Int>()
        val ends = mutableListOf<Int>()
        fun append(value: String, sourceStart: Int, sourceEnd: Int) {
            value.forEach { c ->
                // The tokenizer.json Replace normalizer folds runs of ASCII spaces.
                if (c == ' ' && text.lastOrNull() == ' ') {
                    ends[ends.lastIndex] = sourceEnd
                } else {
                    text.append(c)
                    starts += sourceStart
                    ends += sourceEnd
                }
            }
        }
        byteIndex = 0
        while (byteIndex < bytes.size) {
            val rewrite = normalizer?.match(bytes, byteIndex)
            val length = rewrite?.first ?: utf8Length(bytes[byteIndex])
            val next = minOf(bytes.size, byteIndex + length)
            val value = rewrite?.second ?: String(bytes, byteIndex, next - byteIndex, Charsets.UTF_8)
            append(value, byteToSource[byteIndex], byteToSource[next])
            byteIndex = next
        }
        if (text.isNotEmpty() && text[0] != ' ') {
            text.insert(0, '\u2581')
            starts.add(0, 0)
            ends.add(0, 0)
        }
        for (i in text.indices) if (text[i] == ' ') text.setCharAt(i, '\u2581')
        return Normalized(text.toString(), starts.toIntArray(), ends.toIntArray())
    }

    private fun utf8Length(byte: Byte): Int = when (byte.toInt() and 0xff) {
        in 0..0x7f -> 1
        in 0xc0..0xdf -> 2
        in 0xe0..0xef -> 3
        else -> 4
    }
}

/** SentencePiece's compact Darts double-array trie, including its model-specific Unicode rules. */
internal class PrecompiledNormalizer(blob: ByteArray) {
    private val units: IntArray
    private val replacements: ByteArray

    init {
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        val size = buffer.int
        require(size > 0 && size % 4 == 0 && size <= buffer.remaining())
        units = IntArray(size / 4) { buffer.int }
        replacements = ByteArray(buffer.remaining()).also(buffer::get)
    }

    fun match(input: ByteArray, start: Int): Pair<Int, String>? {
        var node = offset(units[0])
        var length = 0
        var replacement = -1
        for (i in start until input.size) {
            val byte = input[i].toInt() and 0xff
            if (byte == 0) break
            node = node xor byte
            if (node !in units.indices) break
            val unit = units[node]
            if ((unit and (Int.MIN_VALUE or 0xff)) != byte) break
            node = node xor offset(unit)
            if ((unit and 0x100) != 0) {
                if (node !in units.indices) break
                length = i - start + 1
                replacement = units[node] and Int.MAX_VALUE
            }
        }
        if (replacement !in replacements.indices) return null
        var end = replacement
        while (end < replacements.size && replacements[end] != 0.toByte()) end++
        return length to String(replacements, replacement, end - replacement, Charsets.UTF_8)
    }

    private fun offset(unit: Int): Int = (unit ushr 10) shl ((unit and 0x200) ushr 6)
}
