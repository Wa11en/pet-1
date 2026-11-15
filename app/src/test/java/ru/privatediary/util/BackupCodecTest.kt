package ru.privatediary.util

import org.junit.Assert.*
import org.junit.Test
import ru.privatediary.domain.models.Entry
import java.nio.ByteBuffer

class BackupCodecTest {
    private val password = "Пароль для backup 🔐".toCharArray()
    private val entries = listOf(
        Entry(id = 7, timestamp = 1_763_160_425_000L, text = "Тихий вечер у озера.\nI felt at ease. 🌿", mood = 5, tags = listOf("природа", "evening")),
        Entry(id = 8, timestamp = 0L, text = "No mood or tags")
    )

    @Test fun encryptedBackupRestoresAllFieldsInRussianAndEnglish() {
        val encoded = BackupCodec.encode(entries, password)
        assertEquals(entries, BackupCodec.decode(encoded, password))
        assertFalse(encoded.toString(Charsets.UTF_8).contains("Тихий вечер"))
        assertArrayEquals("Пароль для backup 🔐".toCharArray(), password)
    }

    @Test fun randomSaltAndNonceProduceDifferentFiles() {
        assertFalse(BackupCodec.encode(entries, password).contentEquals(BackupCodec.encode(entries, password)))
    }

    @Test fun emptyBackupRoundTrip() {
        assertEquals(emptyList<Entry>(), BackupCodec.decode(BackupCodec.encode(emptyList(), password), password))
    }

    @Test fun wrongPasswordIsRejected() {
        val encoded = BackupCodec.encode(entries, password)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(encoded, "wrong password".toCharArray()) }
    }

    @Test fun authenticatedPayloadAndHeaderDetectTampering() {
        val original = BackupCodec.encode(entries, password)
        val payloadChanged = original.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(payloadChanged, password) }
        val saltChanged = original.copyOf().apply { this[16] = (this[16].toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(saltChanged, password) }
    }

    @Test fun truncatedAndAppendedFilesAreRejected() {
        val encoded = BackupCodec.encode(entries, password)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(encoded.copyOf(encoded.size - 1), password) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(encoded + byteArrayOf(0), password) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(byteArrayOf(1, 2), password) }
    }

    @Test fun maliciousKdfCostIsRejectedBeforeDerivingAKey() {
        val encoded = BackupCodec.encode(entries, password)
        ByteBuffer.wrap(encoded).putInt(12, Int.MAX_VALUE)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(encoded, password) }
    }

    @Test fun unsupportedVersionIsRejected() {
        val encoded = BackupCodec.encode(entries, password)
        ByteBuffer.wrap(encoded).putInt(8, 999)
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(encoded, password) }
    }

    @Test fun boundsAndDuplicateIdsAreValidated() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(List(BackupCodec.MAX_ENTRIES + 1) { entries.first() }, password) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(listOf(entries.first(), entries.first()), password) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(listOf(entries.first().copy(mood = 6)), password) }
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.encode(entries, "123".toCharArray()) }
    }
}
