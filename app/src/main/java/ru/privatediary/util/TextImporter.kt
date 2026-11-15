package ru.privatediary.util

import ru.privatediary.domain.models.Entry
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.DateTimeException
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

object TextImporter {
    const val MAX_FILE_BYTES = 8 * 1024 * 1024
    private val filenameDate = Regex("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?:[T_ ](\\d{2})[-_:](\\d{2})(?:[-_:](\\d{2}))?)?(?!\\d)")

    /** One UTF-8 .txt/.md file becomes one entry; invalid or absent filename date uses [now]. */
    fun import(
        fileName: String,
        bytes: ByteArray,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): Entry {
        val extension = fileName.substringAfterLast('.', "").lowercase(Locale.ROOT)
        require(extension == "txt" || extension == "md") { "Choose a .txt or .md file" }
        require(bytes.size <= MAX_FILE_BYTES) { "File is too large" }
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        require(text.isNotBlank()) { "File is empty" }
        val match = filenameDate.find(fileName)
        val timestamp = match?.let { parsed ->
            try {
                val values = parsed.groupValues
                val date = LocalDate.of(values[1].toInt(), values[2].toInt(), values[3].toInt())
                if (values[4].isEmpty()) date.atStartOfDay(zone).toInstant().toEpochMilli()
                else date.atTime(values[4].toInt(), values[5].toInt(), values[6].ifEmpty { "0" }.toInt())
                    .atZone(zone).toInstant().toEpochMilli()
            } catch (_: DateTimeException) {
                null
            } catch (_: NumberFormatException) {
                null
            }
        } ?: now
        return Entry(timestamp = timestamp, text = text)
    }
}
