package ru.privatediary.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import ru.privatediary.data.repository.FtsQuery
import java.security.SecureRandom
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseTest {
    @Test fun encryptedDatabaseReopensWithItsKeyAndRejectsAnotherKey() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "encryption-test-${UUID.randomUUID()}.db"
        val passphrase = ByteArray(32).also(SecureRandom()::nextBytes)
        val text = "A private lake walk / Личная прогулка у озера"
        var database: DiaryDatabase? = null
        try {
            database = DbFactory.create(context, passphrase.copyOf(), name)
            val id = database.entryDao().upsert(EntryEntity(timestamp = 1_734_854_400_000, text = text, mood = 4, tags = "[\"nature\"]"))
            assertEquals(text, database.entryDao().get(id)?.text)
            assertEquals(listOf(id), database.entryDao().search(FtsQuery.fromUserInput("private lake")).map { it.entry.id })
            assertEquals(listOf(id), database.entryDao().search(FtsQuery.fromUserInput("личная прогул")).map { it.entry.id })
            database.close()
            database = null

            val bytes = context.getDatabasePath(name).readBytes()
            assertTrue(bytes.isNotEmpty())
            assertFalse(bytes.take(16).toByteArray().contentEquals("SQLite format 3\u0000".toByteArray()))
            assertFalse(bytes.toString(Charsets.ISO_8859_1).contains("A private lake walk"))

            database = DbFactory.create(context, passphrase.copyOf(), name)
            assertEquals(text, database.entryDao().get(id)?.text)
            database.close()
            database = null

            val wrongKey = ByteArray(32).also(SecureRandom()::nextBytes)
            var wrongDatabase: DiaryDatabase? = null
            try {
                val failure = runCatching {
                    wrongDatabase = DbFactory.create(context, wrongKey, name)
                    wrongDatabase!!.entryDao().get(id)
                }.exceptionOrNull()
                assertTrue("SQLCipher must reject a different key", failure != null)
            } finally {
                wrongDatabase?.close()
                wrongKey.fill(0)
            }
        } finally {
            database?.close()
            passphrase.fill(0)
            context.deleteDatabase(name)
        }
    }
}
