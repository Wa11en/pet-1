package ru.privatediary.ml

import ru.privatediary.ml.tokenizer.TokenPiece
import ru.privatediary.ml.tokenizer.SentencePieceTokenizer

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class SentencePieceTokenizerTest {
    private fun fixtureJson(charsMap: String? = null): String {
        val normalizer = charsMap?.let { """{"type":"Precompiled","precompiled_charsmap":"$it"}""" }.orEmpty()
        return """
            {"model":{"type":"Unigram","unk_id":3,"vocab":[
                ["<s>",0],["<pad>",0],["</s>",0],["<unk>",0],
                ["▁",-1],["a",-1],["b",-1],["ab",-9],["▁a",-0.1],["<mask>",0]
            ]},"normalizer":{"type":"Sequence","normalizers":[$normalizer]}}
        """.trimIndent()
    }

    private fun fixture(charsMap: String? = null) = SentencePieceTokenizer(fixtureJson(charsMap))

    @Test fun viterbiChoosesBestScoreRatherThanLongestPiece() {
        assertArrayEquals(longArrayOf(0, 8, 6, 2), fixture().encode("ab"))
    }

    @Test fun sourceOffsetsSurvivePrefixAndUnknownCodepoints() {
        val tokens = fixture().tokenize("a🧘🧘")
        assertEquals(listOf(TokenPiece(8, 0, 1), TokenPiece(3, 1, 5)), tokens)
        assertArrayEquals(longArrayOf(0, 2), fixture().encode(""))
        assertArrayEquals(longArrayOf(0, 8, 2), fixture().encode("ab", maxTokens = 3))
    }

    @Test fun readerStreamsVocabularyAndSkipsNestedUnknownFields() {
        val json = fixtureJson()
            .replace("{\"model\"", """{"metadata":{"nested":[null,true,false,{"escaped":"\"\\\/\b\f\n\r\t\uD83E\uDDD8"}]},"model"""")
            .replace("\"a\",-1", "\"\\u0061\",-1.0e0")
        val reader = object : StringReader(json) {
            // A Reader may return arbitrarily small blocks; this must not change parsing.
            override fun read(buffer: CharArray, offset: Int, length: Int): Int =
                super.read(buffer, offset, minOf(length, 1))
        }
        val tokenizer = reader.use { SentencePieceTokenizer(it) }
        assertArrayEquals(longArrayOf(0, 8, 6, 2), tokenizer.encode("ab"))
    }

    @Test fun specialTokensKeepPrecedingWhitespaceAsRequiredByPinnedVocabulary() {
        assertEquals(listOf(TokenPiece(4, 0, 1), TokenPiece(9, 1, 7)), fixture().tokenize(" <mask>"))
    }

    @Test fun truncatedOrInvalidJsonIsRejected() {
        val json = fixtureJson()
        for (invalid in listOf(json.dropLast(1), json + " true", json.replace("-0.1", "NaN"),
            json.replace("\"a\"", "\"\\u00XZ\""), json.replace("\"unk_id\":3", "\"unk_id\":03"))) {
            assertThrows(IllegalArgumentException::class.java) { SentencePieceTokenizer(StringReader(invalid)) }
        }
    }

    @Test fun escapedSurrogatePairKeepsSourceOffsets() {
        val json = fixtureJson().replace("[\"<mask>\",0]", "[\"<mask>\",0],[\"\\uD83E\\uDDD8\",-0.1]")
        val tokenizer = SentencePieceTokenizer(StringReader(json))
        assertEquals(listOf(TokenPiece(4, 0, 0), TokenPiece(10, 0, 2)), tokenizer.tokenize("🧘"))
    }

    @Test fun interpretsModelPrecompiledDartsNormalizer() {
        // A tiny Darts trie encoding the normalization rule "A" -> "a".
        val units = IntArray(68)
        units[0] = 1 shl 10
        units[64] = (66 shl 10) or 0x100 or 65
        units[2] = 0
        val buffer = ByteBuffer.allocate(4 + units.size * 4 + 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(units.size * 4)
        units.forEach(buffer::putInt)
        buffer.put('a'.code.toByte()).put(0)
        val tokenizer = fixture(Base64.getEncoder().encodeToString(buffer.array()))
        assertArrayEquals(longArrayOf(0, 8, 6, 2), tokenizer.encode("Ab"))
    }

    @Test fun optionalRealVocabularyMatchesHuggingFaceRussianAndEnglish() {
        // Run after manually fetching the optional model; no test downloads any assets.
        val file = File(System.getProperty("tokenizerFixturePath") ?: "src/main/assets/model/tokenizer.json")
        assumeTrue("Optional model assets are not installed", file.isFile)
        val tokenizer = file.bufferedReader().use { SentencePieceTokenizer(it) }
        assertArrayEquals(longArrayOf(0,41,1294,12,4222,784,137441,192694,32,2),
            tokenizer.encode("query: когда я чувствовал спокойствие?"))
        assertArrayEquals(longArrayOf(0,41,1294,12,3229,6777,87,12319,116572,32,2),
            tokenizer.encode("query: when did I feel calm?"))
        assertArrayEquals(longArrayOf(0,46692,12,70109,116058,547,84,26291,1082,42266,12276,5,2),
            tokenizer.encode("passage: Сегодня гулял у тихого озера."))
        assertArrayEquals(longArrayOf(0,41,1294,12,47457,26216,33600,31,11373,2),
            tokenizer.encode("query:  ＡＢＣ  cafe\u0301\nhello\tмир"))
    }
}
