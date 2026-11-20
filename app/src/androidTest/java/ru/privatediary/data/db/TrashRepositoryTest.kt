package ru.privatediary.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.privatediary.data.repository.FtsQuery
import ru.privatediary.data.repository.RoomEntryRepository
import ru.privatediary.data.repository.TextHash
import ru.privatediary.domain.models.EditorDraft
import ru.privatediary.domain.models.EmbeddingChunk
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.TrashPolicy
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class TrashRepositoryTest {
    @Test fun softDeletedEntriesDisappearFromEveryActiveReadPath() = runBlocking {
        withDatabase { db, repository ->
            val indexed = insert(repository, "Тихое озеро / quiet lake", mood = 5, tags = listOf("лес"))
            val pending = insert(repository, "Озеро в тумане / morning lake")
            val active = insert(repository, "Работа дома", mood = 3)
            index(repository, indexed)
            repository.saveDraft(EditorDraft.from(indexed).copy(text = "Несохранённый текст"))
            assertEquals(setOf(indexed.id, pending.id), repository.searchWords("lake").map { it.entry.id }.toSet())

            repository.delete(indexed.id)
            repository.delete(pending.id)

            assertEquals(listOf(active), repository.allEntries())
            assertEquals(listOf(active), repository.observeEntries().first())
            assertNull(repository.get(indexed.id))
            assertNull(repository.get(pending.id))
            assertNull(db.entryDao().get(indexed.id))
            assertTrue(repository.searchWords("lake").isEmpty())
            assertTrue(db.entryDao().search(FtsQuery.fromUserInput("lake")).isEmpty())
            assertEquals(listOf(active.id), repository.pendingIndexEntries().map { it.id })
            assertEquals(listOf(active.id), db.entryDao().pending().map { it.id })
            assertTrue(repository.allEmbeddings().isEmpty())
            assertTrue(db.embeddingDao().all().isEmpty())
            assertTrue(repository.observeDrafts().first().isEmpty())
            val trash = repository.observeTrash().first()
            assertEquals(setOf(indexed.id, pending.id), trash.map { it.entry.id }.toSet())
            assertEquals(indexed, trash.single { it.entry.id == indexed.id }.entry)
            assertTrue(trash.all { it.deletedAt > 0L })
            assertEquals(indexed.text, db.entryDao().getTrash(indexed.id)?.text)
            // Soft deletion retains the encrypted index for a cheap, exact restoration.
            assertEquals(1, countRows(db, "embeddings"))
        }
    }

    @Test fun restorationBeforeTheDeadlineKeepsIdentityMetadataAndSearchIndex() = runBlocking {
        withDatabase { db, repository ->
            val original = insert(repository, "Семейная прогулка / family walk", mood = 4, tags = listOf("семья", "walk"))
            index(repository, original)
            repository.delete(original.id)
            val deletedAt = requireNotNull(db.entryDao().getTrash(original.id)?.deletedAt)

            assertTrue(repository.restore(original.id, TrashPolicy.expiresAt(deletedAt) - 1L))
            assertEquals(original, repository.get(original.id))
            assertEquals(listOf(original), repository.allEntries())
            assertEquals(listOf(original.id), repository.searchWords("прогулка").map { it.entry.id })
            assertEquals(listOf(original.id), db.entryDao().search(FtsQuery.fromUserInput("family")).map { it.entry.id })
            assertEquals(listOf(original.id), repository.allEmbeddings().map { it.entryId })
            assertArrayEquals(floatArrayOf(0.6f, 0.8f), repository.allEmbeddings().single().vector, 0f)
            assertTrue(repository.pendingIndexEntries().isEmpty())
            assertTrue(repository.observeTrash().first().isEmpty())
            assertFalse(repository.restore(original.id, deletedAt + 1L))
        }
    }

    @Test fun purgeIncludesTheExactThirtyDayBoundaryAndCascadesOnlyExpiredRows() = runBlocking {
        withDatabase { db, repository ->
            val now = Instant.parse("2026-11-20T00:00:00Z").toEpochMilli()
            val cutoff = TrashPolicy.cutoff(now)
            val older = insert(repository, "Older deleted entry")
            val boundary = insert(repository, "Deleted exactly thirty days ago")
            val recoverable = insert(repository, "Deleted just less than thirty days ago")
            val active = insert(repository, "Active entry must remain")
            for (entry in listOf(older, boundary, recoverable, active)) index(repository, entry)
            assertEquals(1, db.entryDao().moveToTrash(older.id, cutoff - 1L))
            assertEquals(1, db.entryDao().moveToTrash(boundary.id, cutoff))
            assertEquals(1, db.entryDao().moveToTrash(recoverable.id, cutoff + 1L))

            assertEquals(2, repository.purgeExpired(now))
            assertNull(db.entryDao().getTrash(older.id))
            assertNull(db.entryDao().getTrash(boundary.id))
            assertNotNull(db.entryDao().getTrash(recoverable.id))
            assertEquals(listOf(recoverable.id), repository.observeTrash().first().map { it.entry.id })
            assertEquals(listOf(active), repository.allEntries())
            assertEquals(2, countRows(db, "entries"))
            assertEquals(2, countRows(db, "embeddings"))
            assertEquals(0, repository.purgeExpired(now))

            repository.deleteForever(recoverable.id)
            assertTrue(repository.observeTrash().first().isEmpty())
            assertEquals(listOf(active), repository.allEntries())
            assertEquals(1, countRows(db, "entries"))
            assertEquals(1, countRows(db, "embeddings"))

            repository.deleteForever(active.id)
            assertEquals("The trash action must not permanently delete an active entry", active, repository.get(active.id))
        }
    }

    @Test fun restorationAtOrAfterExpirationCannotResurrectAnEntry() = runBlocking {
        withDatabase { db, repository ->
            val now = Instant.parse("2026-11-20T00:00:00Z").toEpochMilli()
            val original = insert(repository, "Expired entry")
            db.entryDao().moveToTrash(original.id, TrashPolicy.cutoff(now))
            assertFalse(repository.restore(original.id, now))
            assertFalse(repository.restore(original.id, now + 1L))
            assertNull(repository.get(original.id))
            assertTrue(repository.allEntries().isEmpty())
            assertTrue(repository.searchWords("Expired").isEmpty())
        }
    }

    @Test fun delayedEditorAndIndexWritesNeverResurrectDeletedEntries() = runBlocking {
        withDatabase { db, repository ->
            val original = insert(repository, "Original private entry")
            index(repository, original)
            repository.saveDraft(EditorDraft.from(original))
            repository.delete(original.id)

            val staleEdit = original.copy(text = "Late editor write")
            assertTrue(runCatching { repository.upsert(staleEdit) }.exceptionOrNull() is IllegalArgumentException)
            assertTrue(runCatching { repository.saveEntryAndClearDraft(staleEdit) }.exceptionOrNull() is IllegalArgumentException)
            repository.saveDraft(EditorDraft.from(staleEdit))
            assertTrue(repository.observeDrafts().first().isEmpty())
            assertFalse(repository.saveEmbeddings(original.id, TextHash.of(original.text), listOf(chunk(original))))
            assertNull(repository.get(original.id))
            assertEquals(original.text, db.entryDao().getTrash(original.id)?.text)
            assertTrue(repository.allEntries().isEmpty())

            repository.deleteForever(original.id)
            assertNull(db.entryDao().getTrash(original.id))
            assertTrue(runCatching { repository.upsert(staleEdit) }.exceptionOrNull() is IllegalArgumentException)
            repository.saveDraft(EditorDraft.from(staleEdit))
            assertFalse(repository.saveEmbeddings(original.id, TextHash.of(original.text), listOf(chunk(original))))
            assertTrue(repository.allEntries().isEmpty())
            assertTrue(repository.observeDrafts().first().isEmpty())
            assertEquals(0, countRows(db, "embeddings"))
        }
    }

    private suspend fun insert(repository: RoomEntryRepository, text: String, mood: Int? = null, tags: List<String> = emptyList()): Entry {
        val entry = Entry(timestamp = 123L, text = text, mood = mood, tags = tags)
        return entry.copy(id = repository.upsert(entry))
    }

    private fun chunk(entry: Entry) = EmbeddingChunk(entry.id, 0, entry.text, floatArrayOf(0.6f, 0.8f), TextHash.of(entry.text))

    private suspend fun index(repository: RoomEntryRepository, entry: Entry) {
        assertTrue(repository.saveEmbeddings(entry.id, TextHash.of(entry.text), listOf(chunk(entry))))
    }

    private fun countRows(database: DiaryDatabase, table: String): Int =
        database.openHelper.writableDatabase.query("SELECT count(*) FROM $table").use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private suspend fun withDatabase(block: suspend (DiaryDatabase, RoomEntryRepository) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "trash-test-${UUID.randomUUID()}.db"
        val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
        var database: DiaryDatabase? = null
        try {
            database = DbFactory.create(context, passphrase.copyOf(), name)
            block(database, RoomEntryRepository(database))
        } finally {
            database?.close()
            passphrase.fill(0)
            context.deleteDatabase(name)
        }
    }
}
