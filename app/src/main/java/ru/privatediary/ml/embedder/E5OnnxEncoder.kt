package ru.privatediary.ml.embedder

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ru.privatediary.ml.search.VectorMath
import ru.privatediary.ml.tokenizer.TextTokenizer
import java.io.Closeable
import java.io.File
import java.nio.LongBuffer

/**
 * The production E5 inference path, independent of Android so it can also be verified on a JVM.
 * The caller serializes inference and closes the encoder when it can no longer be used.
 */
class E5OnnxEncoder(model: File, private val tokenizer: TextTokenizer) : Closeable {
    private val environment = OrtEnvironment.getEnvironment().apply { setTelemetry(false) }
    private val session = OrtSession.SessionOptions().use { options ->
        options.setIntraOpNumThreads(2)
        options.setInterOpNumThreads(1)
        // Chunks vary in length; avoid retaining native arena buffers for the largest input.
        options.setCPUArenaAllocator(false)
        options.setMemoryPatternOptimization(false)
        environment.createSession(model.absolutePath, options)
    }

    fun embedPassage(text: String): FloatArray = embed("passage: $text")

    fun embedQuery(text: String): FloatArray = embed("query: $text")

    private fun embed(text: String): FloatArray {
        val ids = tokenizer.encode(text)
        val mask = LongArray(ids.size) { 1L }
        val shape = longArrayOf(1L, ids.size.toLong())
        val tensors = mutableMapOf<String, OnnxTensor>()
        try {
            tensors["input_ids"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(ids), shape)
            tensors["attention_mask"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(mask), shape)
            if ("token_type_ids" in session.inputNames) {
                tensors["token_type_ids"] = OnnxTensor.createTensor(environment, LongBuffer.wrap(LongArray(ids.size)), shape)
            }
            session.run(tensors).use { outputs ->
                @Suppress("UNCHECKED_CAST")
                val batch = outputs[0].value as Array<Array<FloatArray>>
                require(batch.size == 1) { "Expected a single E5 embedding batch" }
                val vector = VectorMath.meanPool(batch[0], mask)
                require(vector.size == DIMENSIONS) { "Expected multilingual-e5-small's 384 dimensions" }
                return vector
            }
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    override fun close() = session.close()

    companion object {
        const val DIMENSIONS = 384
    }
}
