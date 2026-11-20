package ru.privatediary.util

import org.junit.Assert.*
import org.junit.Test
import ru.privatediary.domain.models.Entry
import java.nio.ByteBuffer
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

class BackupCodecTest {
    private val password = "Пароль для backup 🔐".toCharArray()
    private val entries = listOf(
        Entry(id = 7, timestamp = 1_763_160_425_000L, text = "Тихий вечер у озера.\nI felt at ease. 🌿", mood = 5,
            tags = listOf("природа", "evening"), isFavorite = true),
        Entry(id = 8, timestamp = 0L, text = "No mood or tags")
    )

    @Test fun encryptedVersionTwoBackupRestoresAllFieldsInRussianAndEnglish() {
        val encoded = BackupCodec.encode(entries, password)
        assertEquals(2, ByteBuffer.wrap(encoded).getInt(8))
        assertEquals(entries, BackupCodec.decode(encoded, password))
        assertFalse(encoded.toString(Charsets.UTF_8).contains("Тихий вечер"))
        assertArrayEquals("Пароль для backup 🔐".toCharArray(), password)
    }

    @Test fun immutableVersionOneFixtureRestoresMetadataWithNoFavorites() {
        // This file was generated independently with Python struct.pack, hashlib.pbkdf2_hmac
        // and cryptography.AESGCM, using the released v1 format; never regenerate via encode().
        // Salt = bytes(range(16)), nonce = bytes(range(16,28)); only synthetic test diary data.
        val fixture = requireNotNull(javaClass.getResourceAsStream("/backups/legacy-v1.pdiary")).use { it.readBytes() }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(fixture).joinToString("") { "%02x".format(it) }
        assertEquals("8fba34df3d53e69e34c424e4f8ca4add80fd5cc4bc23e9e1c9d4dfc08cbded7f", sha256)
        assertEquals(1, ByteBuffer.wrap(fixture).getInt(8))
        assertEquals(entries.map { it.copy(isFavorite = false) },
            BackupCodec.decode(fixture, "Legacy test password 2026".toCharArray()))
    }

    @Test fun authenticatedPayloadRejectsFavoriteFlagsOtherThanZeroAndOne() {
        val encoded = BackupCodec.encode(listOf(Entry(timestamp = 1L, text = "Flag validation", isFavorite = true)), password)
        val invalid = withInvalidFavoriteFlag(encoded)
        val error = assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(invalid, password) }
        // The altered payload has a VALID GCM tag: rejection must happen in the format parser.
        assertEquals("Invalid favorite flag", error.message)
    }

    @Test fun changingBetweenSupportedHeaderVersionsStillFailsAuthentication() {
        val encoded = BackupCodec.encode(entries, password)
        ByteBuffer.wrap(encoded).putInt(8, 1)
        val error = assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(encoded, password) }
        assertEquals("Wrong password or damaged backup", error.message)
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

    private fun withInvalidFavoriteFlag(encoded: ByteArray): ByteArray {
        val header = encoded.copyOfRange(0, 48)
        val nonce = header.copyOfRange(32, 44)
        val spec = PBEKeySpec(password, header.copyOfRange(16, 32), 310_000, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        try {
            val decrypt = Cipher.getInstance("AES/GCM/NoPadding")
            decrypt.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            decrypt.updateAAD(header)
            val payload = decrypt.doFinal(encoded, 48, encoded.size - 48)
            try {
                payload[payload.lastIndex] = 2
                // Use a distinct nonce when re-authenticating the deliberately malformed fixture.
                nonce[0] = (nonce[0].toInt() xor 1).toByte()
                nonce.copyInto(header, destinationOffset = 32)
                val encrypt = Cipher.getInstance("AES/GCM/NoPadding")
                encrypt.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
                encrypt.updateAAD(header)
                return header + encrypt.doFinal(payload)
            } finally { payload.fill(0) }
        } finally { key.fill(0) }
    }
}
