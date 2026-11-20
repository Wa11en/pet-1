package ru.privatediary.data.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries ORDER BY timestamp DESC, rowid DESC")
    fun observeAll(): Flow<List<EntryEntity>>
    @Query("SELECT * FROM entries ORDER BY timestamp DESC, rowid DESC")
    suspend fun all(): List<EntryEntity>
    @Query("SELECT * FROM entries WHERE rowid = :id")
    suspend fun get(id: Long): EntryEntity?
    @Upsert suspend fun upsert(entity: EntryEntity): Long
    @Query("DELETE FROM entries WHERE rowid = :id") suspend fun delete(id: Long)
    @Query("DELETE FROM entries") suspend fun deleteAll()
    @Query("SELECT * FROM entries WHERE indexedHash IS NULL OR indexedHash != contentHash")
    suspend fun pending(): List<EntryEntity>
    @Query("UPDATE entries SET indexedHash = :hash WHERE rowid = :id AND contentHash = :hash")
    suspend fun markIndexed(id: Long, hash: String)
    @Query("SELECT entries.*, snippet(entries_fts, '', '', ' … ', 0, 28) AS fragment FROM entries_fts JOIN entries ON entries.rowid = entries_fts.rowid WHERE entries_fts MATCH :query ORDER BY entries.timestamp DESC LIMIT 100")
    suspend fun search(query: String): List<FtsHit>
}

@Dao
interface EmbeddingDao {
    @Query("SELECT embeddings.* FROM embeddings JOIN entries ON entries.rowid = embeddings.entryId WHERE embeddings.contentHash = entries.contentHash AND entries.indexedHash = entries.contentHash")
    suspend fun all(): List<EmbeddingEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAll(chunks: List<EmbeddingEntity>)
    @Query("DELETE FROM embeddings WHERE entryId = :entryId") suspend fun deleteForEntry(entryId: Long)
}

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<DraftEntity>>
    @Query("SELECT * FROM drafts WHERE entryId = :entryId")
    suspend fun get(entryId: Long): DraftEntity?
    @Upsert suspend fun upsert(draft: DraftEntity)
    @Query("DELETE FROM drafts WHERE entryId = :entryId") suspend fun delete(entryId: Long)
    @Query("DELETE FROM drafts") suspend fun deleteAll()
}
