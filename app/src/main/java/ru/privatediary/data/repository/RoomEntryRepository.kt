package ru.privatediary.data.repository

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import ru.privatediary.data.db.*
import ru.privatediary.domain.EntryRepository
import ru.privatediary.domain.EmbeddingStore
import ru.privatediary.domain.DraftRepository
import ru.privatediary.domain.TrashRepository
import ru.privatediary.domain.models.*
import ru.privatediary.util.WordSearch
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

class RoomEntryRepository(val database: DiaryDatabase) : EntryRepository, EmbeddingStore, DraftRepository, TrashRepository {
    private val entries = database.entryDao()
    private val embeddings = database.embeddingDao()
    private val drafts = database.draftDao()
    override fun observeDrafts(): Flow<List<EditorDraft>> = drafts.observeAll().map { rows -> rows.map {
        EditorDraft(it.entryId, it.timestamp, it.text, it.mood, it.rawTags, it.selectionStart, it.selectionEnd, it.updatedAt)
    } }
    override suspend fun saveDraft(draft: EditorDraft) = database.withTransaction {
        val valid = draft.validated()
        if (valid.entryId != 0L && entries.get(valid.entryId) == null) return@withTransaction
        drafts.upsert(DraftEntity(valid.entryId, valid.timestamp, valid.text, valid.mood, valid.rawTags,
            valid.selectionStart, valid.selectionEnd, valid.updatedAt))
    }
    override suspend fun deleteDraft(entryId: Long) { drafts.delete(entryId) }
    suspend fun saveEntryAndClearDraft(entry: Entry): Long = database.withTransaction {
        val id = upsert(entry)
        drafts.delete(entry.id)
        id
    }
    override fun observeEntries(): Flow<List<Entry>> = entries.observeAll().map { list -> list.map { it.toDomain() } }
    override suspend fun get(id: Long): Entry? = entries.get(id)?.toDomain()
    override suspend fun allEntries(): List<Entry> = entries.all().map { it.toDomain() }
    override suspend fun upsert(entry: Entry): Long = database.withTransaction {
        validate(entry)
        val hash = TextHash.of(entry.text)
        val previous = if (entry.id != 0L) entries.get(entry.id) else null
        require(entry.id == 0L || previous != null) { "Entry no longer exists" }
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
    override suspend fun delete(id: Long) = database.withTransaction {
        entries.moveToTrash(id, System.currentTimeMillis()); drafts.delete(id)
    }
    override fun observeTrash(): Flow<List<TrashedEntry>> = entries.observeTrash().map { rows ->
        rows.map { TrashedEntry(it.toDomain(), requireNotNull(it.deletedAt)) }
    }
    override suspend fun restore(id: Long, now: Long): Boolean = database.withTransaction {
        entries.restore(id, TrashPolicy.cutoff(now)) != 0
    }
    override suspend fun deleteForever(id: Long) { entries.deleteTrashed(id) }
    override suspend fun purgeExpired(now: Long): Int = entries.purgeExpired(TrashPolicy.cutoff(now))
    override suspend fun replaceAll(entries: List<Entry>) = database.withTransaction {
        require(entries.map { it.id }.filter { it != 0L }.distinct().size == entries.count { it.id != 0L }) { "Duplicate entry ids" }
        entries.forEach(::validate)
        drafts.deleteAll()
        this.entries.deleteAll()
        entries.forEach { this.entries.upsert(it.toEntity()) }
    }
    override suspend fun searchWords(query: String): List<SearchResult> {
        val fragments = WordSearch.prepare(query)
        if (fragments.isEmpty) return emptyList()
        val expression = FtsQuery.fromUserInput(query)
        // FTS supplies its contextual snippets for indexed prefixes; scanning encrypted rows
        // also finds infixes, which SQLite FTS4 cannot express. SQLite LOWER/NOCASE is not
        // sufficient for case-insensitive Cyrillic on every supported Android version.
        val indexed = if (expression.isBlank()) emptyMap() else entries.search(expression).associateBy { it.entry.id }
        currentCoroutineContext().ensureActive()
        val results = ArrayList<SearchResult>()
        for (row in entries.all()) {
            currentCoroutineContext().ensureActive()
            val entry = row.toDomain()
            if (!fragments.matches(entry.text, entry.tags)) continue
            val indexedFragment = indexed[entry.id]?.fragment
            val fragment = indexedFragment?.takeIf { fragments.rangesIn(it).isNotEmpty() }
                ?: fragments.fragment(entry.text)
            results += SearchResult(entry, fragment)
            if (results.size == 100) break
        }
        return results
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
