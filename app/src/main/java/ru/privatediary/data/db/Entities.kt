package ru.privatediary.data.db

import androidx.room.*

@Entity(tableName = "entries")
data class EntryEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val id: Long = 0,
    val timestamp: Long,
    val text: String,
    val mood: Int?,
    val tags: String,
    val contentHash: String = "",
    val indexedHash: String? = null
)

@Fts4(contentEntity = EntryEntity::class, tokenizer = "unicode61")
@Entity(tableName = "entries_fts")
data class EntryFts(val text: String, val tags: String)

@Entity(tableName = "embeddings", primaryKeys = ["entryId", "ordinal"],
    foreignKeys = [ForeignKey(entity = EntryEntity::class, parentColumns = ["rowid"], childColumns = ["entryId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("entryId")])
data class EmbeddingEntity(val entryId: Long, val ordinal: Int, val fragment: String, val vector: ByteArray, val contentHash: String)

data class FtsHit(@Embedded val entry: EntryEntity, val fragment: String)
