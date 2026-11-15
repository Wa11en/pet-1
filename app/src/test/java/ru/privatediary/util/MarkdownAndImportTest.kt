package ru.privatediary.util

import org.junit.Assert.*
import org.junit.Test
import ru.privatediary.domain.models.Entry
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.ZoneId
import java.util.zip.ZipInputStream

class MarkdownAndImportTest {
    @Test fun zipHasUniqueSafeNamesAndUtf8TextWithMetadata() {
        val original = Entry(id = 1, timestamp = 1_763_160_425_000L, text = "День без спешки.\nAt peace.", mood = 4,
            tags = listOf("лес", "quote\"\nnew line", "../unsafe"))
        val bytes = MarkdownExporter.export(listOf(original, original.copy(id = 2)))
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes), Charsets.UTF_8).use { zip ->
            repeat(2) {
                val entry = requireNotNull(zip.nextEntry)
                assertTrue(entry.name.endsWith(".md"))
                assertFalse(entry.name.contains('/'))
                assertTrue(names.add(entry.name))
                val document = zip.readBytes().toString(Charsets.UTF_8)
                assertTrue(document.contains(original.text))
                assertTrue(document.contains("mood: 4"))
                assertTrue(document.contains("quote\\\"\\nnew line"))
                zip.closeEntry()
            }
            assertNull(zip.nextEntry)
        }
    }

    @Test fun importsRussianUtf8AndFilenameDateTime() {
        val entry = TextImporter.import("2026-11-15_13-45-07_walk.md", "\uFEFFТихий парк 🌿".toByteArray(),
            now = 1L, zone = ZoneId.of("UTC"))
        assertEquals("Тихий парк 🌿", entry.text)
        assertEquals(Instant.parse("2026-11-15T13:45:07Z").toEpochMilli(), entry.timestamp)
    }

    @Test fun dateOnlyUsesChosenTimezone() {
        val entry = TextImporter.import("2026-11-15.txt", "Morning".toByteArray(), now = 1,
            zone = ZoneId.of("Europe/Moscow"))
        assertEquals(Instant.parse("2026-11-14T21:00:00Z").toEpochMilli(), entry.timestamp)
    }

    @Test fun absentOrInvalidDateUsesProvidedNow() {
        assertEquals(42L, TextImporter.import("untitled.MD", "Hello".toByteArray(), now = 42).timestamp)
        assertEquals(42L, TextImporter.import("2026-02-30.txt", "Hello".toByteArray(), now = 42).timestamp)
        assertEquals(42L, TextImporter.import("2026-11-15_25-00.md", "Hello".toByteArray(), now = 42).timestamp)
    }

    @Test fun rejectsInvalidEncodingEmptyContentAndUnsupportedFiles() {
        assertThrows(IllegalArgumentException::class.java) { TextImporter.import("note.exe", "hi".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { TextImporter.import("note.txt", " \n".toByteArray()) }
        assertThrows(java.nio.charset.CharacterCodingException::class.java) { TextImporter.import("note.md", byteArrayOf(0xC3.toByte(), 0x28)) }
        assertThrows(IllegalArgumentException::class.java) { TextImporter.import("note.txt", ByteArray(TextImporter.MAX_FILE_BYTES + 1)) }
    }
}
