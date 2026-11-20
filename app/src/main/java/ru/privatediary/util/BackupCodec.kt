package ru.privatediary.util

import ru.privatediary.domain.models.Entry
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** A portable, authenticated backup; independent of Android and the device Keystore. */
object BackupCodec {
    const val MAX_FILE_BYTES = 100 * 1024 * 1024
    const val MAX_ENTRIES = 10_000
    private const val MAX_TEXT_BYTES = 8 * 1024 * 1024
    private const val MAX_TAG_BYTES = 1024
    private const val MAX_TAGS = 128
    private const val ROUNDS = 310_000
    private const val VERSION = 2
    private const val HEADER_BYTES = 48
    private val magic = "PDIARYBK".toByteArray(Charsets.US_ASCII)
    private val random = SecureRandom()

    fun encode(entries: List<Entry>, password: CharArray): ByteArray {
        require(password.size >= 8) { "Use a backup password of at least 8 characters" }
        require(entries.size <= MAX_ENTRIES) { "Too many entries" }
        val plaintext = serialize(entries)
        val salt = ByteArray(16).also(random::nextBytes)
        val nonce = ByteArray(12).also(random::nextBytes)
        val header = ByteArrayOutputStream(HEADER_BYTES).also { stream ->
            DataOutputStream(stream).use { out ->
                out.write(magic)
                out.writeInt(VERSION)
                out.writeInt(ROUNDS)
                out.write(salt)
                out.write(nonce)
                out.writeInt(plaintext.size + 16)
            }
        }.toByteArray()
        val key = deriveKey(password, salt)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(header)
            header + cipher.doFinal(plaintext)
        } finally {
            key.fill(0)
            plaintext.fill(0)
        }
    }

    /** Rejects truncation, appended data, unsupported versions, wrong passwords, and tampering. */
    fun decode(data: ByteArray, password: CharArray): List<Entry> {
        require(data.size in (HEADER_BYTES + 16)..MAX_FILE_BYTES) { "Invalid backup size" }
        require(password.isNotEmpty()) { "Password is empty" }
        val input = DataInputStream(ByteArrayInputStream(data))
        val storedMagic = ByteArray(magic.size).also(input::readFully)
        require(storedMagic.contentEquals(magic)) { "Not a Private Diary backup" }
        val version = input.readInt()
        require(version in 1..VERSION) { "Unsupported backup version" }
        // A fixed, versioned KDF prevents untrusted files from requesting unbounded CPU work.
        require(input.readInt() == ROUNDS) { "Unsupported backup key derivation" }
        val salt = ByteArray(16).also(input::readFully)
        val nonce = ByteArray(12).also(input::readFully)
        require(input.readInt() == data.size - HEADER_BYTES) { "Truncated or appended backup" }
        val key = deriveKey(password, salt)
        val plaintext = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            cipher.updateAAD(data, 0, HEADER_BYTES)
            cipher.doFinal(data, HEADER_BYTES, data.size - HEADER_BYTES)
        } catch (error: GeneralSecurityException) {
            throw IllegalArgumentException("Wrong password or damaged backup", error)
        } finally {
            key.fill(0)
        }
        return try {
            deserialize(plaintext, version)
        } finally {
            plaintext.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password, salt, ROUNDS, 256)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun serialize(entries: List<Entry>): ByteArray {
        val buffer = ByteArrayOutputStream()
        val positiveIds = HashSet<Long>()
        DataOutputStream(buffer).use { out ->
            out.writeInt(entries.size)
            for (entry in entries) {
                require(entry.id >= 0 && (entry.id == 0L || positiveIds.add(entry.id))) { "Invalid entry id" }
                require(entry.mood == null || entry.mood in 1..5) { "Invalid mood" }
                require(entry.tags.size <= MAX_TAGS) { "Too many tags" }
                out.writeLong(entry.id)
                out.writeLong(entry.timestamp)
                out.writeByte(entry.mood ?: 0)
                writeString(out, entry.text, MAX_TEXT_BYTES)
                out.writeInt(entry.tags.size)
                entry.tags.forEach { writeString(out, it, MAX_TAG_BYTES) }
                out.writeByte(if (entry.isFavorite) 1 else 0)
                require(buffer.size() <= MAX_FILE_BYTES - HEADER_BYTES - 16) { "Backup is too large" }
            }
        }
        return buffer.toByteArray()
    }

    private fun deserialize(bytes: ByteArray, version: Int): List<Entry> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val count = input.readInt()
        require(count in 0..MAX_ENTRIES) { "Invalid entry count" }
        val positiveIds = HashSet<Long>()
        val entries = ArrayList<Entry>(count)
        repeat(count) {
            val id = input.readLong()
            require(id >= 0 && (id == 0L || positiveIds.add(id))) { "Invalid entry id" }
            val timestamp = input.readLong()
            val mood = input.readUnsignedByte()
            require(mood in 0..5) { "Invalid mood" }
            val text = readString(input, MAX_TEXT_BYTES)
            val tagsCount = input.readInt()
            require(tagsCount in 0..MAX_TAGS) { "Invalid tags count" }
            val tags = List(tagsCount) { readString(input, MAX_TAG_BYTES) }
            val favorite = if (version >= 2) input.readUnsignedByte() else 0
            require(favorite in 0..1) { "Invalid favorite flag" }
            entries += Entry(id = id, timestamp = timestamp, text = text, mood = mood.takeIf { it != 0 }, tags = tags,
                isFavorite = favorite == 1)
        }
        require(input.available() == 0) { "Unexpected backup payload" }
        return entries
    }

    private fun writeString(out: DataOutputStream, value: String, limit: Int) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size <= limit) { "Entry or tag is too large" }
        out.writeInt(encoded.size)
        out.write(encoded)
    }

    private fun readString(input: DataInputStream, limit: Int): String {
        val size = input.readInt()
        require(size in 0..limit && size <= input.available()) { "Invalid string size" }
        val encoded = ByteArray(size).also(input::readFully)
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(encoded)).toString()
    }
}
