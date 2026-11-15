package ru.privatediary.util

import ru.privatediary.domain.models.Entry
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Deliberately unencrypted: the SAF destination is chosen explicitly by the user. */
object MarkdownExporter {
    private val fileDate = DateTimeFormatter.ofPattern("uuuu-MM-dd_HH-mm-ss_SSS", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun export(entries: List<Entry>): ByteArray = ByteArrayOutputStream().also { write(entries, it) }.toByteArray()

    /** Streaming variant avoids holding the entire ZIP in memory. Closes [destination]. */
    fun write(entries: List<Entry>, destination: OutputStream) {
        ZipOutputStream(destination, Charsets.UTF_8).use { zip ->
            entries.forEachIndexed { index, entry ->
                val instant = Instant.ofEpochMilli(entry.timestamp)
                val fileName = "${fileDate.format(instant)}_${index + 1}.md"
                zip.putNextEntry(ZipEntry(fileName).apply { time = entry.timestamp.coerceAtLeast(0) })
                val document = buildString {
                    append("---\ndate: ").append(quoted(instant.toString())).append('\n')
                    entry.mood?.let { append("mood: ").append(it).append('\n') }
                    append("tags: [").append(entry.tags.joinToString(", ") { quoted(it) }).append("]\n---\n\n")
                    append(entry.text).append('\n')
                }
                zip.write(document.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun quoted(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 32) append("\\u%04x".format(Locale.ROOT, char.code)) else append(char)
            }
        }
        append('"')
    }
}
