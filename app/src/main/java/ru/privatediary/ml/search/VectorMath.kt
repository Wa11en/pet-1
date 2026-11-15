package ru.privatediary.ml.search

import kotlin.math.sqrt

object VectorMath {
    fun normalize(vector: FloatArray): FloatArray {
        require(vector.all { it.isFinite() }) { "Non-finite embedding" }
        val norm = sqrt(vector.sumOf { it.toDouble() * it.toDouble() })
        return if (norm == 0.0) FloatArray(vector.size) else FloatArray(vector.size) { (vector[it] / norm).toFloat() }
    }

    fun meanPool(hiddenStates: Array<FloatArray>, attentionMask: LongArray): FloatArray {
        require(hiddenStates.isNotEmpty() && hiddenStates.size == attentionMask.size)
        val dimensions = hiddenStates.first().size
        require(dimensions > 0 && hiddenStates.all { it.size == dimensions })
        require(attentionMask.all { it == 0L || it == 1L })
        val count = attentionMask.sum()
        require(count > 0)
        val sums = DoubleArray(dimensions)
        hiddenStates.forEachIndexed { index, row ->
            if (attentionMask[index] == 1L) row.forEachIndexed { dimension, value ->
                require(value.isFinite())
                sums[dimension] += value
            }
        }
        return normalize(FloatArray(dimensions) { (sums[it] / count).toFloat() })
    }

    fun cosine(left: FloatArray, right: FloatArray): Float {
        require(left.isNotEmpty() && left.size == right.size)
        require(left.all { it.isFinite() } && right.all { it.isFinite() })
        var dot = 0.0
        var a = 0.0
        var b = 0.0
        left.indices.forEach { i ->
            dot += left[i].toDouble() * right[i]
            a += left[i].toDouble() * left[i]
            b += right[i].toDouble() * right[i]
        }
        return if (a == 0.0 || b == 0.0) 0f else (dot / sqrt(a * b)).coerceIn(-1.0, 1.0).toFloat()
    }
}

data class EmbeddingCandidate(val entryId: Long, val fragment: String, val embedding: FloatArray)
data class SemanticHit(val entryId: Long, val fragment: String, val similarity: Float)

object CosineRanking {
    /** The best chunk represents each entry, so a long entry cannot occupy all result slots. */
    fun rank(query: FloatArray, candidates: List<EmbeddingCandidate>, limit: Int = 30): List<SemanticHit> {
        require(limit >= 0)
        if (query.isEmpty() || query.any { !it.isFinite() }) return emptyList()
        return candidates.asSequence()
            .filter { it.embedding.size == query.size && it.embedding.all(Float::isFinite) }
            .map { SemanticHit(it.entryId, it.fragment, VectorMath.cosine(query, it.embedding)) }
            .groupBy { it.entryId }.values.map { chunks -> chunks.maxBy { it.similarity } }
            .sortedWith(compareByDescending<SemanticHit> { it.similarity }.thenBy { it.entryId })
            .take(limit)
    }
}
