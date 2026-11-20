package ru.privatediary.ml.search

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Public model prototypes only. Implementations must never store diary fragments or queries. */
interface TopicPrototypeCache {
    suspend fun load(): ByteArray?
    suspend fun save(bytes: ByteArray)
}

/** A bounded, model/catalog-bound cache. A malformed cache is discarded and regenerated. */
object TopicPrototypeCodec {
    const val VECTOR_COUNT = 240
    const val DIMENSIONS = 384
    const val MODEL_SHA256 = "4d24e2bc01a447951524466ef533e52944bf48509e6552810bcee1a2711cb02c"

    private val magic = "PDTPVEC1".toByteArray(Charsets.US_ASCII)
    private const val VERSION = 1
    private const val DIGEST_BYTES = 32
    private const val HEADER_BYTES = 8 + 4 + DIGEST_BYTES * 2 + 4 + 4
    private const val CONTENT_BYTES = HEADER_BYTES + VECTOR_COUNT * DIMENSIONS * 4
    const val ENCODED_BYTES = CONTENT_BYTES + DIGEST_BYTES
    private val hashPattern = Regex("[0-9a-f]{64}")

    fun encode(catalogSha: String, vectors: List<FloatArray>): ByteArray {
        require(hashPattern.matches(catalogSha)) { "Invalid catalog SHA-256" }
        require(vectors.size == VECTOR_COUNT && vectors.all(::validVector)) { "Invalid topic prototypes" }
        val output = ByteBuffer.allocate(ENCODED_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        output.put(magic).putInt(VERSION)
        output.put(hashBytes(MODEL_SHA256)).put(hashBytes(catalogSha))
        output.putInt(VECTOR_COUNT).putInt(DIMENSIONS)
        vectors.forEach { vector -> vector.forEach { output.putFloat(it) } }
        val bytes = output.array()
        output.put(digest(bytes, CONTENT_BYTES))
        return bytes
    }

    fun decode(catalogSha: String, bytes: ByteArray): List<FloatArray>? {
        if (!hashPattern.matches(catalogSha) || bytes.size != ENCODED_BYTES) return null
        if (!MessageDigest.isEqual(digest(bytes, CONTENT_BYTES), bytes.copyOfRange(CONTENT_BYTES, ENCODED_BYTES))) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val storedMagic = ByteArray(magic.size).also { input.get(it) }
        if (!storedMagic.contentEquals(magic) || input.int != VERSION) return null
        val modelHash = ByteArray(DIGEST_BYTES).also { input.get(it) }
        val catalogHash = ByteArray(DIGEST_BYTES).also { input.get(it) }
        if (!modelHash.contentEquals(hashBytes(MODEL_SHA256)) || !catalogHash.contentEquals(hashBytes(catalogSha))) return null
        if (input.int != VECTOR_COUNT || input.int != DIMENSIONS) return null
        val vectors = ArrayList<FloatArray>(VECTOR_COUNT)
        repeat(VECTOR_COUNT) {
            val vector = FloatArray(DIMENSIONS) { input.float }
            if (!validVector(vector)) return null
            vectors += vector
        }
        return vectors
    }

    private fun validVector(vector: FloatArray): Boolean {
        if (vector.size != DIMENSIONS || vector.any { !it.isFinite() }) return false
        val squaredNorm = vector.sumOf { it.toDouble() * it }
        return squaredNorm in 0.99..1.01
    }

    private fun hashBytes(value: String): ByteArray = ByteArray(DIGEST_BYTES) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }

    private fun digest(bytes: ByteArray, length: Int): ByteArray = MessageDigest.getInstance("SHA-256").run {
        update(bytes, 0, length)
        digest()
    }
}
