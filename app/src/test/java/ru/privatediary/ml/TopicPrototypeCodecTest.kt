package ru.privatediary.ml

import org.junit.Assert.*
import org.junit.Test
import ru.privatediary.ml.search.TopicPrototypeCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

class TopicPrototypeCodecTest {
    private val catalog = "1".repeat(64)
    private fun prototypes() = List(TopicPrototypeCodec.VECTOR_COUNT) { index ->
        FloatArray(TopicPrototypeCodec.DIMENSIONS).also { it[index % it.size] = 1f }
    }

    @Test fun modelBoundCachePreservesEveryPrototypeAndRejectsAnotherCatalog() {
        val original = prototypes()
        val encoded = TopicPrototypeCodec.encode(catalog, original)
        val restored = TopicPrototypeCodec.decode(catalog, encoded)!!
        original.zip(restored).forEach { (expected, actual) -> assertArrayEquals(expected, actual, 0f) }
        assertNull(TopicPrototypeCodec.decode("2".repeat(64), encoded))
        assertNull(TopicPrototypeCodec.decode("invalid", encoded))
    }

    @Test fun truncatedExtendedAndCorruptedPayloadsAreRejected() {
        val encoded = TopicPrototypeCodec.encode(catalog, prototypes())
        assertNull(TopicPrototypeCodec.decode(catalog, encoded.copyOf(encoded.size - 1)))
        assertNull(TopicPrototypeCodec.decode(catalog, encoded + byteArrayOf(0)))
        val corrupt = encoded.copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        assertNull(TopicPrototypeCodec.decode(catalog, corrupt))
    }

    @Test fun aValidChecksumCannotHideDifferentModelOrWrongDimensions() {
        val encoded = TopicPrototypeCodec.encode(catalog, prototypes())
        val otherModel = encoded.copyOf().also { it[12] = (it[12].toInt() xor 1).toByte() }
        assertNull(TopicPrototypeCodec.decode(catalog, withChecksum(otherModel)))
        val wrongDimensions = encoded.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(80, 383)
        }
        assertNull(TopicPrototypeCodec.decode(catalog, withChecksum(wrongDimensions)))
    }

    @Test fun nonFiniteAndNonUnitVectorsAreRejectedEvenWithValidChecksum() {
        val encoded = TopicPrototypeCodec.encode(catalog, prototypes())
        val nonFinite = encoded.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(84, Float.NaN)
        }
        assertNull(TopicPrototypeCodec.decode(catalog, withChecksum(nonFinite)))
        val zeroVector = encoded.copyOf().also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putFloat(84, 0f)
        }
        assertNull(TopicPrototypeCodec.decode(catalog, withChecksum(zeroVector)))
        assertThrows(IllegalArgumentException::class.java) {
            TopicPrototypeCodec.encode(catalog, prototypes().drop(1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TopicPrototypeCodec.encode(catalog, prototypes().toMutableList().also { it[0] = FloatArray(384) })
        }
    }

    private fun withChecksum(bytes: ByteArray): ByteArray {
        val contentLength = bytes.size - 32
        val digest = MessageDigest.getInstance("SHA-256").run { update(bytes, 0, contentLength); digest() }
        digest.copyInto(bytes, contentLength)
        return bytes
    }
}
