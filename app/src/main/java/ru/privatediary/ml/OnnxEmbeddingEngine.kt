package ru.privatediary.ml

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.LongBuffer

/** All model initialization, tokenization and inference run off the UI thread. */
class OnnxEmbeddingEngine(context: Context) : EmbeddingEngine {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    @Volatile override var availability: EngineAvailability = EngineAvailability.MODEL_MISSING
        private set
    private var attempted = false
    private var tokenizer: SentencePieceTokenizer? = null
    private var session: OrtSession? = null
    private var environment: OrtEnvironment? = null

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
                SentencePieceTokenizer(it.readText())
            }
            // ONNX needs a seekable model. This copy contains public model weights, never diary text.
            val directory = File(appContext.noBackupFilesDir, "embedding-model").apply { mkdirs() }
            val modelFile = File(directory, "e5-small-761b726d-int8.onnx")
            if (!modelFile.isFile || modelFile.length() != MODEL_SIZE) {
                val temporary = File(directory, "model.part")
                try {
                    appContext.assets.open("model/model_int8.onnx").use { input ->
                        temporary.outputStream().use { output -> input.copyTo(output) }
                    }
                    check(temporary.length() == MODEL_SIZE) { "Incomplete model" }
                    check(temporary.renameTo(modelFile)) { "Cannot install local model" }
                } finally {
                    temporary.delete()
                }
            }
            val ort = OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
            val loadedSession = OrtSession.SessionOptions().use { options ->
                options.setIntraOpNumThreads(2)
                options.setInterOpNumThreads(1)
                ort.createSession(modelFile.absolutePath, options)
            }
            tokenizer = loadedTokenizer
            environment = ort
            session = loadedSession
            availability = EngineAvailability.READY
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            availability = EngineAvailability.UNAVAILABLE
        } catch (_: LinkageError) {
            // An unsupported native ABI must not prevent the rest of the diary from opening.
            availability = EngineAvailability.UNAVAILABLE
        }
        return availability
    }

    override suspend fun embedPassage(text: String): FloatArray? = embed("passage: $text")
    override suspend fun embedQuery(text: String): FloatArray? = embed("query: $text")

    override suspend fun chunk(text: String): List<TextChunk> = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (prepareLocked() != EngineAvailability.READY) return@withLock emptyList()
            EntryChunker(checkNotNull(tokenizer)).chunk(text)
        }
    }

    private suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (prepareLocked() != EngineAvailability.READY) return@withLock null
            try {
                val ids = checkNotNull(tokenizer).encode(text)
                val mask = LongArray(ids.size) { 1L }
                val ort = checkNotNull(environment)
                val loadedSession = checkNotNull(session)
                val shape = longArrayOf(1L, ids.size.toLong())
                val tensors = mutableMapOf<String, OnnxTensor>()
                try {
                    tensors["input_ids"] = OnnxTensor.createTensor(ort, LongBuffer.wrap(ids), shape)
                    tensors["attention_mask"] = OnnxTensor.createTensor(ort, LongBuffer.wrap(mask), shape)
                    if ("token_type_ids" in loadedSession.inputNames) {
                        tensors["token_type_ids"] = OnnxTensor.createTensor(ort, LongBuffer.wrap(LongArray(ids.size)), shape)
                    }
                    loadedSession.run(tensors).use { outputs ->
                        @Suppress("UNCHECKED_CAST")
                        val batch = outputs[0].value as Array<Array<FloatArray>>
                        VectorMath.meanPool(batch[0], mask)
                    }
                } finally {
                    tensors.values.forEach { it.close() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // The repository can immediately switch to FTS if the bundled assets are incompatible.
                availability = EngineAvailability.UNAVAILABLE
                runCatching { session?.close() }
                session = null
                null
            } catch (_: LinkageError) {
                availability = EngineAvailability.UNAVAILABLE
                null
            }
        }
    }

    private companion object {
        const val MODEL_SIZE = 118_054_593L
    }
}
