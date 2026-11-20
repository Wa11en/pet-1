package ru.privatediary.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [EntryEntity::class, EntryFts::class, EmbeddingEntity::class, DraftEntity::class], version = 3, exportSchema = true)
abstract class DiaryDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun embeddingDao(): EmbeddingDao
    abstract fun draftDao(): DraftDao
}

object DbFactory {
    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) { db.execSQL("ALTER TABLE entries ADD COLUMN deletedAt INTEGER") }
    }
    val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS drafts (entryId INTEGER NOT NULL, timestamp INTEGER NOT NULL, text TEXT NOT NULL, mood INTEGER, rawTags TEXT NOT NULL, selectionStart INTEGER NOT NULL, selectionEnd INTEGER NOT NULL, updatedAt INTEGER NOT NULL, PRIMARY KEY(entryId))")
        }
    }
    /** Must be called on Dispatchers.IO after unlocking; never uses destructive migrations. */
    fun create(context: Context, passphrase: ByteArray, name: String = "diary.db"): DiaryDatabase {
        System.loadLibrary("sqlcipher")
        val keyCopy = passphrase.copyOf()
        val database = Room.databaseBuilder(context.applicationContext, DiaryDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(keyCopy))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
            .build()
        return try { database.openHelper.writableDatabase; database }
            catch (failure: Throwable) { database.close(); keyCopy.fill(0); throw failure }
    }
}
