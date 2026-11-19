package ru.privatediary.ml.embedder

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import ru.privatediary.ml.tokenizer.EntryChunker
import ru.privatediary.ml.tokenizer.SentencePieceTokenizer
import ru.privatediary.ml.tokenizer.TextChunk
import java.io.File

/** All model initialization, tokenization and inference run off the UI thread. */
class OnnxEmbeddingEngine(context: Context) : EmbeddingEngine {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    @Volatile override var availability: EngineAvailability = EngineAvailability.MODEL_MISSING
        private set
    private var attempted = false
    private var tokenizer: SentencePieceTokenizer? = null
    private var encoder: E5OnnxEncoder? = null

    override suspend fun prepare(): EngineAvailability = withContext(Dispatchers.IO) {
        mutex.withLock { prepareLocked() }
    }

    private fun prepareLocked(): EngineAvailability {
        if (attempted) return availability
        attempted = true
        val files = try {
            appContext.assets.list("model")?.toSet().orEmpty()
        } catch (_: Exception) {
            availability = EngineAvailability.UNAVAILABLE
            return availability
        }
        if ("model_int8.onnx" !in files || "tokenizer.json" !in files) {
            availability = EngineAvailability.MODEL_MISSING
            return availability
        }
        try {
            val loadedTokenizer = appContext.assets.open("model/tokenizer.json").bufferedReader().use {
                SentencePieceTokenizer(it)
            }
            // ONNX Runtime opens a private file; this contains public model weights, never diary data.
            val directory = File(appContext.noBackupFilesDir, "embedding-model").apply { mkdirs() }
            val model = File(directory, "e5-small-761b726d-int8.onnx")
            if (!model.isFile || model.length() != MODEL_SIZE) {
                val temporary = File(directory, "model.part")
                try {
                    appContext.assets.open("model/model_int8.onnx").use { input -> temporary.outputStream().use(input::copyTo) }
                    check(temporary.length() == MODEL_SIZE) { "Incomplete bundled model" }
                    check(temporary.renameTo(model)) { "Cannot install bundled model" }
                } finally {
                    temporary.delete()
                }
            }
            val loadedEncoder = E5OnnxEncoder(model, loadedTokenizer)
            tokenizer = loadedTokenizer
            encoder = loadedEncoder
            availability = EngineAvailability.READY
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            disable()
        } catch (_: LinkageError) {
            disable()
        } catch (_: OutOfMemoryError) {
            disable()
        }
        return availability
    }

    override suspend fun embedPassage(text: String): FloatArray? = embed { embedPassage(text) }
    override suspend fun embedQuery(text: String): FloatArray? = embed { embedQuery(text) }

    override suspend fun chunk(text: String): List<TextChunk> = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (prepareLocked() != EngineAvailability.READY) return@withLock emptyList()
            try {
                EntryChunker(checkNotNull(tokenizer)).chunk(text)
            } catch (_: OutOfMemoryError) {
                disable()
                emptyList()
            }
        }
    }

    private suspend fun embed(operation: E5OnnxEncoder.() -> FloatArray): FloatArray? = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (prepareLocked() != EngineAvailability.READY) return@withLock null
            try {
                checkNotNull(encoder).operation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                disable()
                null
            } catch (_: LinkageError) {
                disable()
                null
            } catch (_: OutOfMemoryError) {
                disable()
                null
            }
        }
    }

    private fun disable() {
        availability = EngineAvailability.UNAVAILABLE
        runCatching { encoder?.close() }
        encoder = null
        tokenizer = null
    }

    private companion object {
        const val MODEL_SIZE = 118_054_593L
    }
}
