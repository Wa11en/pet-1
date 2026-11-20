package ru.privatediary.data.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.privatediary.ml.search.TopicPrototypeCache
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Public model metadata only. Diary text, query text and diary vectors never enter this file. */
class FileTopicPrototypeCache(private val directory: File) : TopicPrototypeCache {
    private val target get() = File(directory, "topics-v1.bin")

    override suspend fun load(): ByteArray? = withContext(Dispatchers.IO) {
        val file = target
        if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) null
        else file.inputStream().use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > MAX_BYTES) return@withContext null
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    override suspend fun save(bytes: ByteArray) = withContext(Dispatchers.IO) {
        require(bytes.size <= MAX_BYTES)
        check(directory.isDirectory || directory.mkdirs())
        val temporary = File(directory, "topics-v1.tmp")
        try {
            FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
        Unit
    }

    private companion object { const val MAX_BYTES = 1024 * 1024 }
}
