package ru.privatediary

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.privatediary.data.db.DbFactory
import ru.privatediary.data.repository.RoomEntryRepository
import ru.privatediary.data.repository.FileTopicPrototypeCache
import ru.privatediary.domain.usecases.SearchEntries
import ru.privatediary.ml.embedder.OnnxEmbeddingEngine
import ru.privatediary.ml.search.SemanticSearchService
import ru.privatediary.ml.search.TopicRelevanceEngine
import ru.privatediary.security.DiarySettings
import ru.privatediary.security.keystore.KeystoreVault
import ru.privatediary.security.lock.PinLock

/** Manual DI. The database and model are opened lazily after authentication, off the main thread. */
class AppGraph(private val context: Context) {
    val settings = DiarySettings(context)
    val lock = PinLock(context, settings)
    private val openMutex = Mutex()
    private var session: DiarySession? = null

    suspend fun session(): DiarySession = withContext(Dispatchers.IO) {
        check(lock.unlocked.value)
        openMutex.withLock {
            check(lock.unlocked.value)
            session ?: run {
                val passphrase = KeystoreVault(context).databasePassphrase()
                val db = try { DbFactory.create(context, passphrase) } finally { passphrase.fill(0) }
                val repository = RoomEntryRepository(db)
                val embedder = OnnxEmbeddingEngine(context)
                val relevance = TopicRelevanceEngine(embedder,
                    FileTopicPrototypeCache(java.io.File(context.noBackupFilesDir, "semantic-topics")))
                val semantic = SemanticSearchService(embedder, repository, repository, relevance)
                DiarySession(repository, semantic, SearchEntries(repository, semantic::search)).also { session = it }
            }
        }
    }
}

class DiarySession(
    val repository: RoomEntryRepository,
    val semantic: SemanticSearchService,
    val search: SearchEntries,
)
