package ru.privatediary.domain

import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.EmbeddingChunk

interface EmbeddingStore {
    suspend fun pendingIndexEntries(): List<Entry>
    suspend fun allEmbeddings(): List<EmbeddingChunk>
    suspend fun saveEmbeddings(entryId: Long, expectedTextHash: String, chunks: List<EmbeddingChunk>): Boolean
}
