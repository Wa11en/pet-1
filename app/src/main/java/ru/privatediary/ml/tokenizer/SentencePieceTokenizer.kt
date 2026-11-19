package ru.privatediary.ml.tokenizer

import java.io.Reader
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/**
 * XLM-R SentencePiece Unigram: model scores, Viterbi segmentation, model-provided
 * precompiled Unicode normalization and Metaspace. No JNI and no Android APIs.
 * The vocabulary in Hugging Face tokenizer.json already contains XLM-R token IDs.
 * The caller owns [reader] and closes it after construction.
 */
class SentencePieceTokenizer(reader: Reader) : TextTokenizer {
    constructor(json: String) : this(StringReader(json))

    private data class Piece(val id: Int, val score: Double)
    private val vocabulary: Map<String, Piece>
    private val longestPiece: Int
    private val unknownId: Int
    private val unknownScore: Double
    private val normalizer: PrecompiledNormalizer?
    // The pinned XLM-R added tokens all use lstrip=false/rstrip=false.
    private val specialTokens = Regex("<mask>|<s>|</s>|<pad>|<unk>")

    init {
        // Stream the 250k vocabulary rows: retaining JSONObject's complete parse tree
        // beside the final map would substantially increase the Android heap peak.
        val input = TokenizerJsonReader(reader)
        val pieces = HashMap<String, Piece>()
        var modelType: String? = null
        var loadedUnknownId: Int? = null
        var longest = 0
        var minimum = Double.POSITIVE_INFINITY
        var charsMap: PrecompiledNormalizer? = null
        input.objectFields { field ->
            when (field) {
                "model" -> input.objectFields { modelField ->
                    when (modelField) {
                        "type" -> modelType = input.string()
                        "unk_id" -> loadedUnknownId = input.integer()
                        "vocab" -> {
                            var id = 0
                            input.arrayValues {
                                input.expect('[')
                                val text = input.string()
                                input.expect(',')
                                val score = input.number()
                                input.expect(']')
                                require(text.isNotEmpty() && score.isFinite())
                                require(pieces.put(text, Piece(id++, score)) == null) { "Duplicate vocabulary piece" }
                                longest = maxOf(longest, text.length)
                                minimum = minOf(minimum, score)
                            }
                        }
                        else -> input.skipValue()
                    }
                }
                "normalizer" -> input.objectFields { normalizerField ->
                    if (normalizerField == "normalizers") {
                        input.arrayValues {
                            var type: String? = null
                            var encodedMap: String? = null
                            input.objectFields { itemField ->
                                when (itemField) {
                                    "type" -> type = input.string()
                                    "precompiled_charsmap" -> encodedMap = input.string()
                                    else -> input.skipValue()
                                }
                            }
                            if (type == "Precompiled") {
                                charsMap = PrecompiledNormalizer(Base64.getDecoder().decode(requireNotNull(encodedMap)))
                            }
                        }
                    } else input.skipValue()
                }
                else -> input.skipValue()
            }
        }
        input.finish()
        require(modelType == "Unigram") { "Expected XLM-R SentencePiece Unigram" }
        require(pieces["<s>"]?.id == 0 && pieces["</s>"]?.id == 2)
        unknownId = requireNotNull(loadedUnknownId)
        require(unknownId in 0 until pieces.size)
        vocabulary = pieces
        longestPiece = longest
        unknownScore = minimum - 10.0
        normalizer = charsMap
    }

    override fun tokenize(text: String): List<TokenPiece> {
        if (text.isEmpty()) return emptyList()
        val output = mutableListOf<TokenPiece>()
        var start = 0
        specialTokens.findAll(text).forEach { match ->
            if (match.range.first > start) output += tokenizePart(text.substring(start, match.range.first), start)
            val symbol = match.value
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
        val starts = IntBuffer(original.length + 1)
        val ends = IntBuffer(original.length + 1)
        fun append(value: String, sourceStart: Int, sourceEnd: Int) {
            value.forEach { c ->
                // The tokenizer.json Replace normalizer folds runs of ASCII spaces.
                if (c == ' ' && text.lastOrNull() == ' ') {
                    ends[ends.size - 1] = sourceEnd
                } else {
                    text.append(c)
                    starts.add(sourceStart)
                    ends.add(sourceEnd)
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
            starts.prepend(0)
            ends.prepend(0)
        }
        for (i in text.indices) if (text[i] == ' ') text.setCharAt(i, '\u2581')
        return Normalized(text.toString(), starts.toArray(), ends.toArray())
    }

    private fun utf8Length(byte: Byte): Int = when (byte.toInt() and 0xff) {
        in 0..0x7f -> 1
        in 0xc0..0xdf -> 2
        in 0xe0..0xef -> 3
        else -> 4
    }
}

/** Primitive offsets avoid retaining two boxed Integer objects for every source character. */
private class IntBuffer(initialCapacity: Int) {
    private var values = IntArray(maxOf(16, initialCapacity))
    var size = 0
        private set

    operator fun get(index: Int): Int = values[index]
    operator fun set(index: Int, value: Int) { values[index] = value }

    fun add(value: Int) {
        ensureCapacity()
        values[size++] = value
    }

    fun prepend(value: Int) {
        ensureCapacity()
        values.copyInto(values, 1, 0, size)
        values[0] = value
        size++
    }

    fun toArray(): IntArray = values.copyOf(size)

    private fun ensureCapacity() {
        if (size == values.size) values = values.copyOf(values.size * 2)
    }
}

/** Small forward-only reader: vocabulary rows are consumed without building a JSON object tree. */
private class TokenizerJsonReader(reader: Reader) {
    private val source = reader.buffered()
    private var lookahead = UNREAD

    fun objectFields(field: (String) -> Unit) {
        expect('{')
        if (consume('}')) return
        while (true) {
            val name = string()
            expect(':')
            field(name)
            if (consume('}')) return
            expect(',')
        }
    }

    fun arrayValues(value: () -> Unit) {
        expect('[')
        if (consume(']')) return
        while (true) {
            value()
            if (consume(']')) return
            expect(',')
        }
    }

    fun string(): String {
        expect('"')
        val text = StringBuilder()
        while (true) {
            val next = read()
            require(next >= 0) { "Unexpected end of tokenizer JSON string" }
            when (val char = next.toChar()) {
                '"' -> return text.toString()
                '\\' -> {
                    val escaped = read()
                    require(escaped >= 0) { "Unexpected end of tokenizer JSON escape" }
                    text.append(when (escaped.toChar()) {
                        '"' -> '"'
                        '\\' -> '\\'
                        '/' -> '/'
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> {
                            var codepoint = 0
                            repeat(4) {
                                val digit = read()
                                val value = if (digit >= 0) digit.toChar().digitToIntOrNull(16) else null
                                require(value != null) { "Invalid tokenizer JSON Unicode escape" }
                                codepoint = (codepoint shl 4) or value
                            }
                            codepoint.toChar()
                        }
                        else -> error("Invalid tokenizer JSON escape")
                    })
                }
                else -> {
                    require(char >= ' ') { "Unescaped control character in tokenizer JSON" }
                    text.append(char)
                }
            }
        }
    }

    fun number(): Double {
        whitespace()
        val literal = StringBuilder()
        while (peek() >= 0 && peek().toChar() in NUMBER_CHARS) literal.append(read().toChar())
        require(NUMBER.matches(literal)) { "Invalid tokenizer JSON number" }
        return literal.toString().toDouble().also { require(it.isFinite()) }
    }

    fun integer(): Int {
        val value = number()
        require(value >= Int.MIN_VALUE && value <= Int.MAX_VALUE && value == value.toInt().toDouble())
        return value.toInt()
    }

    fun skipValue(depth: Int = 0) {
        require(depth <= 64) { "Tokenizer JSON nesting is too deep" }
        whitespace()
        when (peek().toChar()) {
            '{' -> objectFields { skipValue(depth + 1) }
            '[' -> arrayValues { skipValue(depth + 1) }
            '"' -> string()
            't' -> literal("true")
            'f' -> literal("false")
            'n' -> literal("null")
            else -> number()
        }
    }

    fun finish() {
        whitespace()
        require(peek() < 0) { "Trailing data in tokenizer JSON" }
    }

    fun expect(char: Char) {
        whitespace()
        require(read() == char.code) { "Expected '$char' in tokenizer JSON" }
    }

    private fun consume(char: Char): Boolean {
        whitespace()
        return if (peek() == char.code) { read(); true } else false
    }

    private fun literal(value: String) {
        value.forEach { require(read() == it.code) { "Invalid tokenizer JSON literal" } }
    }

    private fun whitespace() {
        while (peek() == ' '.code || peek() == '\n'.code || peek() == '\r'.code || peek() == '\t'.code) read()
    }

    private fun peek(): Int {
        if (lookahead == UNREAD) lookahead = source.read()
        return lookahead
    }

    private fun read(): Int = peek().also { lookahead = UNREAD }

    private companion object {
        const val UNREAD = -2
        const val NUMBER_CHARS = "-+0123456789.eE"
        val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
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
