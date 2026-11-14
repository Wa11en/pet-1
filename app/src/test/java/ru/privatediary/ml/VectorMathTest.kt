package ru.privatediary.ml

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

class VectorMathTest {
    @Test fun paddingIsExcludedBeforePoolingAndL2Normalization() {
        val pooled = VectorMath.meanPool(
            arrayOf(floatArrayOf(1f, 3f), floatArrayOf(3f, 1f), floatArrayOf(999f, 999f)),
            longArrayOf(1L, 1L, 0L),
        )
        assertArrayEquals(floatArrayOf(1f / sqrt(2f), 1f / sqrt(2f)), pooled, 0.00001f)
        assertEquals(1f, VectorMath.cosine(pooled, pooled), 0.00001f)
    }

    @Test fun rankingUsesCosineAndBestChunkPerEntry() {
        val ranked = CosineRanking.rank(floatArrayOf(1f, 0f), listOf(
            EmbeddingCandidate(1, "плохой фрагмент", floatArrayOf(0f, 1f)),
            EmbeddingCandidate(2, "средний фрагмент", floatArrayOf(8f, 6f)),
            EmbeddingCandidate(1, "лучший фрагмент", floatArrayOf(100f, 0f)),
            EmbeddingCandidate(3, "обратное направление", floatArrayOf(-1f, 0f)),
        ))
        assertEquals(listOf(1L, 2L, 3L), ranked.map { it.entryId })
        assertEquals("лучший фрагмент", ranked.first().fragment)
        assertEquals(0.8f, ranked[1].similarity, 0.00001f)
        assertEquals(2, CosineRanking.rank(floatArrayOf(1f, 0f), listOf(
            EmbeddingCandidate(1, "1", floatArrayOf(1f, 0f)),
            EmbeddingCandidate(2, "2", floatArrayOf(0f, 1f)),
            EmbeddingCandidate(3, "3", floatArrayOf(-1f, 0f)),
        ), 2).size)
    }

    @Test fun zeroVectorsAndInvalidCandidatesDoNotBreakSearch() {
        assertEquals(0f, VectorMath.cosine(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)), 0f)
        assertArrayEquals(floatArrayOf(0f, 0f), VectorMath.normalize(floatArrayOf(0f, 0f)), 0f)
        assertTrue(CosineRanking.rank(floatArrayOf(1f, 0f), listOf(
            EmbeddingCandidate(1, "wrong dimension", floatArrayOf(1f)),
            EmbeddingCandidate(2, "corrupt", floatArrayOf(Float.NaN, 0f)),
        )).isEmpty())
    }
}
