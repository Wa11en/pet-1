package ru.privatediary.data.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.privatediary.data.repository.FloatBlobs
import ru.privatediary.data.repository.FtsQuery
import ru.privatediary.data.repository.RoomEntryRepository
import ru.privatediary.data.repository.TextHash
import ru.privatediary.domain.models.EditorDraft
import ru.privatediary.domain.models.Entry
import java.security.SecureRandom
import java.util.UUID

/** Runs against real SQLCipher and Room, including the schema shipped before drafts existed. */
@RunWith(AndroidJUnit4::class)
class DiaryUpgradeTest {
    @Test fun encryptedDraftRetainsRawInputAndCursorAfterReopening() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "draft-reopen-${UUID.randomUUID()}.db"
        val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
        var database: DiaryDatabase? = null
        val draft = EditorDraft(timestamp = 1_763_160_425_000L, text = "Незаконченная запись / unfinished 🌿", mood = 4,
            rawTags = " #семья, прогулка, ", selectionStart = 3, selectionEnd = 10, updatedAt = 12_345L)
        try {
            database = DbFactory.create(context, passphrase.copyOf(), name)
            val repository = RoomEntryRepository(database)
            repository.saveDraft(draft)
            assertTrue(repository.allEntries().isEmpty())
            assertTrue(repository.pendingIndexEntries().isEmpty())
            database.close()
            database = null

            val disk = context.getDatabasePath(name).readBytes()
            assertFalse(disk.take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray()))
            assertFalse(disk.toString(Charsets.UTF_8).contains(draft.text))

            database = DbFactory.create(context, passphrase.copyOf(), name)
            assertEquals(listOf(draft), RoomEntryRepository(database).observeDrafts().first())
            assertTrue(RoomEntryRepository(database).searchWords("незаконченная").isEmpty())
        } finally {
            database?.close()
            passphrase.fill(0)
            context.deleteDatabase(name)
        }
    }

    @Test fun publishingAndClearingTheDraftAreOneTransaction() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "draft-transaction-${UUID.randomUUID()}.db"
        val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
        var database: DiaryDatabase? = null
        try {
            database = DbFactory.create(context, passphrase.copyOf(), name)
            val repository = RoomEntryRepository(database)
            val draft = EditorDraft(timestamp = 123L, text = "Сохраняемая запись", rawTags = "лес,", updatedAt = 456L)
            repository.saveDraft(draft)

            // Force the SECOND write to fail, proving that the already-inserted entry rolls back.
            database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_draft_clear BEFORE DELETE ON drafts BEGIN SELECT RAISE(ABORT, 'test: draft clear failed'); END")
            val failure = runCatching { repository.saveEntryAndClearDraft(draft.toEntry()) }.exceptionOrNull()
            assertNotNull("A failed draft deletion must abort the whole save", failure)
            assertTrue(repository.allEntries().isEmpty())
            assertEquals(listOf(draft), repository.observeDrafts().first())

            database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_draft_clear")
            val id = repository.saveEntryAndClearDraft(draft.toEntry())
            assertTrue(id > 0L)
            assertEquals(draft.toEntry().copy(id = id), repository.get(id))
            assertTrue(repository.observeDrafts().first().isEmpty())

            val editedDraft = EditorDraft.from(requireNotNull(repository.get(id))).copy(text = "Исправленная запись", updatedAt = 789L)
            repository.saveDraft(editedDraft)
            assertEquals(id, repository.saveEntryAndClearDraft(editedDraft.toEntry()))
            assertEquals(listOf(editedDraft.toEntry()), repository.allEntries())
            assertTrue(repository.observeDrafts().first().isEmpty())
        } finally {
            database?.close()
            passphrase.fill(0)
            context.deleteDatabase(name)
        }
    }

    @Test fun genuineVersionOneDatabaseMigratesWithoutLosingEntriesFtsOrEmbeddings() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "version-one-upgrade-${UUID.randomUUID()}.db"
        val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
        val original = Entry(id = 41L, timestamp = 1_763_160_425_000L,
            text = "A private lake walk / Личная прогулка у озера", mood = 4, tags = listOf("лес"))
        val vector = floatArrayOf(0.6f, 0.8f)
        var database: DiaryDatabase? = null
        try {
            createVersionOneDatabase(context, name, passphrase, original, vector)
            database = DbFactory.create(context, passphrase.copyOf(), name)
            val repository = RoomEntryRepository(database)
            assertTrue(database.openHelper.writableDatabase.version >= 2)
            assertEquals(listOf(original), repository.allEntries())
            assertEquals(listOf(original.id), repository.searchWords("личная прогул").map { it.entry.id })
            assertEquals(listOf(original.id), database.entryDao().search(FtsQuery.fromUserInput("private lake")).map { it.entry.id })
            val chunks = repository.allEmbeddings()
            assertEquals(1, chunks.size)
            assertEquals(original.id, chunks.single().entryId)
            assertEquals(original.text, chunks.single().fragment)
            assertArrayEquals(vector, chunks.single().vector, 0f)
            assertTrue(repository.observeDrafts().first().isEmpty())

            val draft = EditorDraft.from(original).copy(text = "Черновик после обновления",
                selectionStart = 7, selectionEnd = 12, updatedAt = 123L)
            repository.saveDraft(draft)
            database.close()
            database = null
            database = DbFactory.create(context, passphrase.copyOf(), name)
            val reopened = RoomEntryRepository(database)
            assertEquals(original, reopened.get(original.id))
            assertEquals(listOf(draft), reopened.observeDrafts().first())
        } finally {
            database?.close()
            passphrase.fill(0)
            context.deleteDatabase(name)
        }
    }

    private fun createVersionOneDatabase(context: Context, name: String, key: ByteArray, entry: Entry, vector: FloatArray) {
        System.loadLibrary("sqlcipher")
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val schema = assets.open("ru.privatediary.data.db.DiaryDatabase/1.json").bufferedReader().use {
            JSONObject(it.readText()).getJSONObject("database")
        }
        assertEquals(1, schema.getInt("version"))
        val callback = object : SupportSQLiteOpenHelper.Callback(1) {
            override fun onCreate(db: SupportSQLiteDatabase) {
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val tableName = entity.getString("tableName")
                    fun create(sql: String) = db.execSQL(sql.replace("\${TABLE_NAME}", tableName))
                    create(entity.getString("createSql"))
                    entity.optJSONArray("indices")?.let { indices ->
                        for (j in 0 until indices.length()) create(indices.getJSONObject(j).getString("createSql"))
                    }
                    entity.optJSONArray("contentSyncTriggers")?.let { triggers ->
                        for (j in 0 until triggers.length()) create(triggers.getString(j))
                    }
                }
                val setup = schema.getJSONArray("setupQueries")
                for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            }

            override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {
                error("The seeding helper only creates the genuine version-one schema")
            }
        }
        val config = SupportSQLiteOpenHelper.Configuration.builder(context).name(name).callback(callback).build()
        SupportOpenHelperFactory(key.copyOf()).create(config).use { helper ->
            val db = helper.writableDatabase
            assertEquals(1, db.version)
            val hash = TextHash.of(entry.text)
            db.execSQL("INSERT INTO entries(rowid, timestamp, text, mood, tags, contentHash, indexedHash) VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf(entry.id, entry.timestamp, entry.text, entry.mood, "[\"лес\"]", hash, hash))
            db.execSQL("INSERT INTO embeddings(entryId, ordinal, fragment, vector, contentHash) VALUES (?, ?, ?, ?, ?)",
                arrayOf(entry.id, 0, entry.text, FloatBlobs.encode(vector), hash))
        }
    }
}
