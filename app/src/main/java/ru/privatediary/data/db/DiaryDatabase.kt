package ru.privatediary.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [EntryEntity::class, EntryFts::class, EmbeddingEntity::class], version = 1, exportSchema = true)
abstract class DiaryDatabase : RoomDatabase() {
    abstract fun entryDao(): EntryDao
    abstract fun embeddingDao(): EmbeddingDao
}

object DbFactory {
    /** Must be called on Dispatchers.IO after unlocking; never uses destructive migrations. */
    fun create(context: Context, passphrase: ByteArray, name: String = "diary.db"): DiaryDatabase {
        System.loadLibrary("sqlcipher")
        val keyCopy = passphrase.copyOf()
        val database = Room.databaseBuilder(context.applicationContext, DiaryDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(keyCopy))
            .build()
        return try { database.openHelper.writableDatabase; database }
            catch (failure: Throwable) { database.close(); keyCopy.fill(0); throw failure }
    }
}
