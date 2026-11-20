package ru.privatediary.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.privatediary.data.repository.RoomEntryRepository
import ru.privatediary.data.repository.TextHash
import ru.privatediary.domain.models.EditorDraft
import ru.privatediary.domain.models.EmbeddingChunk
import ru.privatediary.domain.models.Entry
import java.security.SecureRandom
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FavoriteRepositoryTest {
    @Test fun favoriteSurvivesEncryptedReopeningAndCanBeTurnedOff() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "favorite-reopen-${UUID.randomUUID()}.db"
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        var database: DiaryDatabase? = null
        try {
            database = DbFactory.create(context, key.copyOf(), name)
            var repository = RoomEntryRepository(database)
            val original = insert(repository, "Важное воспоминание")
            assertFalse(requireNotNull(repository.get(original.id)).isFavorite)
            repository.setFavorite(original.id, true)
            assertEquals(listOf(original.copy(isFavorite = true)), repository.observeEntries().first())
            database.close()
            database = null

            database = DbFactory.create(context, key.copyOf(), name)
            repository = RoomEntryRepository(database)
            assertEquals(original.copy(isFavorite = true), repository.get(original.id))
            repository.setFavorite(original.id, false)
            assertEquals(original, repository.get(original.id))
            assertEquals(listOf(original), repository.allEntries())
        } finally {
            database?.close()
            key.fill(0)
            context.deleteDatabase(name)
        }
    }

    @Test fun favoriteTogglePreservesTheTextHashAndExistingEmbeddingIndex() = runBlocking {
        withDatabase { database, repository ->
            val original = insert(repository, "Тихая прогулка у озера")
            val hash = TextHash.of(original.text)
            val vector = floatArrayOf(0.6f, 0.8f)
            assertTrue(repository.saveEmbeddings(original.id, hash,
                listOf(EmbeddingChunk(original.id, 0, original.text, vector, hash))))
            val before = requireNotNull(database.entryDao().get(original.id))
            assertEquals(hash, before.indexedHash)

            for (favorite in listOf(true, false, true)) {
                repository.setFavorite(original.id, favorite)
                val row = requireNotNull(database.entryDao().get(original.id))
                assertEquals(before.contentHash, row.contentHash)
                assertEquals(before.indexedHash, row.indexedHash)
                assertEquals(before.text, row.text)
                assertEquals(before.timestamp, row.timestamp)
                assertEquals(before.mood, row.mood)
                assertEquals(before.tags, row.tags)
                assertTrue(repository.pendingIndexEntries().isEmpty())
                val chunk = repository.allEmbeddings().single()
                assertEquals(original.id, chunk.entryId)
                assertEquals(original.text, chunk.fragment)
                assertEquals(hash, chunk.contentHash)
                assertArrayEquals(vector, chunk.vector, 0f)
                assertEquals(listOf(original.id), repository.searchWords("озера").map { it.entry.id })
                assertEquals(favorite, requireNotNull(repository.get(original.id)).isFavorite)
            }
        }
    }

    @Test fun delayedFavoriteActionsNeitherModifyTrashNorResurrectDeletedEntries() = runBlocking {
        withDatabase { database, repository ->
            val original = insert(repository, "Запись в избранном", favorite = true)
            repository.delete(original.id)
            val trashed = requireNotNull(database.entryDao().getTrash(original.id))
            repository.setFavorite(original.id, false)
            assertEquals(trashed, database.entryDao().getTrash(original.id))
            assertNull(repository.get(original.id))
            assertTrue(repository.allEntries().isEmpty())
            assertTrue(repository.observeEntries().first().isEmpty())

            repository.deleteForever(original.id)
            repository.setFavorite(original.id, true)
            repository.setFavorite(Long.MAX_VALUE, true)
            assertNull(database.entryDao().getTrash(original.id))
            assertNull(repository.get(original.id))
            assertTrue(repository.allEntries().isEmpty())
        }
    }

    @Test fun publishingAnEditedDraftPreservesTheLatestFavoriteState() = runBlocking {
        withDatabase { _, repository ->
            val original = insert(repository, "Первоначальная запись")
            val draft = EditorDraft.from(original).copy(text = "Черновик с изменённым текстом")
            repository.saveDraft(draft)
            repository.setFavorite(original.id, true)
            // Drafts do not contain favorite state, so their Entry defaults to false.
            repository.saveEntryAndClearDraft(draft.toEntry())
            assertEquals(draft.toEntry().copy(isFavorite = true), repository.get(original.id))
            assertTrue(repository.observeDrafts().first().isEmpty())

            val secondDraft = EditorDraft.from(requireNotNull(repository.get(original.id)))
                .copy(text = "Следующая правка")
            repository.saveDraft(secondDraft)
            repository.setFavorite(original.id, false)
            // Even a stale editor Entry with its old star must not undo a recent toggle.
            repository.saveEntryAndClearDraft(secondDraft.toEntry().copy(isFavorite = true))
            assertEquals(secondDraft.toEntry().copy(isFavorite = false), repository.get(original.id))
            assertTrue(repository.observeDrafts().first().isEmpty())
        }
    }

    @Test fun explicitRepositoryUpsertsHonorTheProvidedFavoriteValue() = runBlocking {
        withDatabase { _, repository ->
            val original = insert(repository, "Звезда при создании", favorite = true)
            assertEquals(original, repository.get(original.id))
            repository.upsert(original.copy(isFavorite = false))
            assertEquals(original.copy(isFavorite = false), repository.get(original.id))
            repository.upsert(original.copy(text = "Обновлённая запись", isFavorite = true))
            assertEquals(original.copy(text = "Обновлённая запись", isFavorite = true), repository.get(original.id))
        }
    }

    private suspend fun insert(repository: RoomEntryRepository, text: String, favorite: Boolean = false): Entry {
        val original = Entry(timestamp = 1_763_160_425_000L, text = text, mood = 4, tags = listOf("важное"), isFavorite = favorite)
        return original.copy(id = repository.upsert(original))
    }

    private suspend fun withDatabase(block: suspend (DiaryDatabase, RoomEntryRepository) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "favorite-test-${UUID.randomUUID()}.db"
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        var database: DiaryDatabase? = null
        try {
            database = DbFactory.create(context, key.copyOf(), name)
            block(database, RoomEntryRepository(database))
        } finally {
            database?.close()
            key.fill(0)
            context.deleteDatabase(name)
        }
    }
}
