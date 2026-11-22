package ru.privatediary.monetization.ads

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.security.SecureRandom

/** A short lived, single use launch permit. Contains no diary or installation identifiers. */
internal class AdEntryTicketStore(private val directory: File) {
    fun issue(wallTimeMillis: Long, elapsedTimeMillis: Long): String = locked {
        val nonce = ByteArray(24).also { SecureRandom().nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        DataOutputStream(File(directory, TICKET_FILE).outputStream()).use {
            it.writeUTF(nonce)
            it.writeLong(wallTimeMillis)
            it.writeLong(elapsedTimeMillis)
        }
        nonce
    }

    /** Delete before validating: a rejected ticket can never be retried. */
    fun consume(wallTimeMillis: Long, elapsedTimeMillis: Long): String? = locked {
        val ticket = File(directory, TICKET_FILE)
        if (!ticket.isFile) return@locked null
        val bytes = try { ticket.takeIf { it.length() <= 256 }?.readBytes() } finally { ticket.delete() }
            ?: return@locked null
        runCatching {
            DataInputStream(bytes.inputStream()).use {
                val nonce = it.readUTF()
                val wallAge = wallTimeMillis - it.readLong()
                val elapsedAge = elapsedTimeMillis - it.readLong()
                nonce.takeIf { value ->
                    value.matches(Regex("[a-f0-9]{48}")) &&
                        wallAge in 0..LIFETIME_MILLIS && elapsedAge in 0..LIFETIME_MILLIS &&
                        it.available() == 0
                }
            }
        }.getOrNull()
    }

    fun revoke() = locked { File(directory, TICKET_FILE).delete(); Unit }

    private fun <T> locked(block: () -> T): T {
        check(directory.isDirectory || directory.mkdirs()) { "Ad launch directory unavailable" }
        return RandomAccessFile(File(directory, LOCK_FILE), "rw").use { file ->
            file.channel.lock().use { block() }
        }
    }

    private companion object {
        const val TICKET_FILE = "entry.ticket"
        const val LOCK_FILE = "entry.lock"
        const val LIFETIME_MILLIS = 15_000L
    }
}
