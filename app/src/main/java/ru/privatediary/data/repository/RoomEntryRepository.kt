package ru.privatediary.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import ru.privatediary.data.db.*
import ru.privatediary.domain.EntryRepository
import ru.privatediary.domain.EmbeddingStore
import ru.privatediary.domain.models.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

object TextHash {
    fun of(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

object FloatBlobs {
    fun encode(vector: FloatArray): ByteArray = ByteBuffer.allocate(vector.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .apply { vector.forEach { putFloat(it) } }.array()
    fun decode(blob: ByteArray): FloatArray {
        require(blob.size % 4 == 0) { "Malformed embedding" }
        val buffer = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(blob.size / 4) { buffer.float }
    }
}

class RoomEntryRepository(val database: DiaryDatabase) : EntryRepository, EmbeddingStore {
    private val entries = database.entryDao()
    private val embeddings = database.embeddingDao()
    override fun observeEntries(): Flow<List<Entry>> = entries.observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun get(id: Long): Entry? = entries.get(id)?.toDomain()
    override suspend fun allEntries(): List<Entry> = entries.all().map { it.toDomain() }
    override suspend fun upsert(entry: Entry): Long = database.withTransaction {
        validate(entry)
        val hash = TextHash.of(entry.text)
        val previous = if (entry.id != 0L) entries.get(entry.id) else null
        val row = entry.toEntity(hash, previous?.indexedHash?.takeIf { previous.contentHash == hash })
        val inserted = entries.upsert(row)
        val id = if (entry.id != 0L) entry.id else inserted
        if (previous != null && previous.contentHash != hash) embeddings.deleteForEntry(id)
        id
    }
    override suspend fun importEntries(entries: List<Entry>): Unit = database.withTransaction {
        require(entries.size <= 1000)
        entries.forEach(::validate)
        entries.forEach { this.entries.upsert(it.copy(id = 0).toEntity()) }
    }
    override suspend fun delete(id: Long) { entries.delete(id) }
    override suspend fun replaceAll(entries: List<Entry>) = database.withTransaction {
        require(entries.map { it.id }.filter { it != 0L }.distinct().size == entries.count { it.id != 0L }) { "Duplicate entry ids" }
        entries.forEach(::validate)
        this.entries.deleteAll()
        entries.forEach { this.entries.upsert(it.toEntity()) }
    }
    override suspend fun searchWords(query: String): List<SearchResult> {
        val expression = FtsQuery.fromUserInput(query)
        if (expression.isBlank()) return emptyList()
        return entries.search(expression).map { SearchResult(it.entry.toDomain(), it.fragment.ifBlank { it.entry.text.take(260) }) }
    }
    override suspend fun pendingIndexEntries(): List<Entry> = entries.pending().map { it.toDomain() }
    override suspend fun allEmbeddings(): List<EmbeddingChunk> = embeddings.all().map {
        EmbeddingChunk(it.entryId, it.ordinal, it.fragment, FloatBlobs.decode(it.vector), it.contentHash)
    }
    /** Hash guard prevents an old indexing result overwriting a later edit. */
    override suspend fun saveEmbeddings(entryId: Long, expectedTextHash: String, chunks: List<EmbeddingChunk>): Boolean = database.withTransaction {
        val current = entries.get(entryId) ?: return@withTransaction false
        if (current.contentHash != expectedTextHash) return@withTransaction false
        require(chunks.all { it.entryId == entryId && it.contentHash == expectedTextHash && it.vector.all(Float::isFinite) })
        embeddings.deleteForEntry(entryId)
        embeddings.insertAll(chunks.map { EmbeddingEntity(it.entryId, it.ordinal, it.fragment, FloatBlobs.encode(it.vector), it.contentHash) })
        entries.markIndexed(entryId, expectedTextHash)
        true
    }
    private fun validate(entry: Entry) {
        require(entry.id >= 0 && entry.text.isNotBlank() && entry.text.length <= 1_000_000)
        require(entry.mood == null || entry.mood in 1..5)
        require(entry.tags.size <= 50 && entry.tags.all { it.length <= 100 })
    }
}

private fun EntryEntity.toDomain(): Entry {
    val values = JSONArray(tags)
    return Entry(id, timestamp, text, mood, List(values.length()) { values.getString(it) })
}
private fun Entry.toEntity(hash: String = TextHash.of(text), indexedHash: String? = null): EntryEntity =
    EntryEntity(id, timestamp, text, mood, JSONArray(tags).toString(), hash, indexedHash)
