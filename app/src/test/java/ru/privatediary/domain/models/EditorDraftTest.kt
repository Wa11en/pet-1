package ru.privatediary.domain.models

import org.junit.Assert.*
import org.junit.Test

class EditorDraftTest {
    @Test fun rawTagInputIsPreservedUntilTheEntryIsPublished() {
        val raw = " #лес, отдых, #лес, , незаконченный, "
        val draft = EditorDraft(timestamp = 123L, text = "Тихий вечер", rawTags = raw, updatedAt = 456L)

        val validated = draft.validated()
        assertEquals(raw, validated.rawTags)
        assertEquals(listOf("лес", "отдых", "незаконченный"), validated.toEntry().tags)
        assertEquals(raw, draft.rawTags)
        assertEquals(456L, validated.updatedAt)
    }

    @Test fun recoveredCursorOffsetsAreClampedToTheCurrentText() {
        val draft = EditorDraft(timestamp = 1L, text = "Привет 🌿", selectionStart = -50, selectionEnd = Int.MAX_VALUE)
        val recovered = draft.validated()
        assertEquals(0, recovered.selectionStart)
        assertEquals(draft.text.length, recovered.selectionEnd)
        assertEquals(draft.text, recovered.text)
    }

    @Test fun anExistingBackwardSelectionKeepsItsDirection() {
        val draft = EditorDraft(timestamp = 1L, text = "A quiet evening", selectionStart = 12, selectionEnd = 2)
        assertEquals(draft, draft.validated())
    }

    @Test fun newAndEmptyDraftsAreValidBeforeTheyBecomeEntries() {
        val draft = EditorDraft(timestamp = 1L, text = "", rawTags = "#семья,", selectionStart = 7, selectionEnd = 7)
        val validated = draft.validated()
        assertEquals(0L, validated.entryId)
        assertEquals("", validated.text)
        assertEquals(0, validated.selectionStart)
        assertEquals(0, validated.selectionEnd)
        assertEquals("#семья,", validated.rawTags)
    }

    @Test fun openingAnEntryKeepsMetadataAndPlacesTheCursorAtTheEnd() {
        val entry = Entry(id = 17L, timestamp = 321L, text = "Walk / Прогулка 🌿", mood = 4, tags = listOf("лес", "evening"))
        val draft = EditorDraft.from(entry)
        assertEquals(entry, draft.toEntry())
        assertEquals("лес, evening", draft.rawTags)
        assertEquals(entry.text.length, draft.selectionStart)
        assertEquals(entry.text.length, draft.selectionEnd)
    }

    @Test fun draftStorageAcceptsItsLimitsAndRejectsInvalidMetadata() {
        val atLimit = EditorDraft(timestamp = 0L, text = "x".repeat(1_000_000), rawTags = "т".repeat(6_000), mood = 5)
        assertEquals(atLimit, atLimit.validated())
        for (invalid in listOf(
            EditorDraft(entryId = -1L, timestamp = 0L),
            EditorDraft(timestamp = 0L, text = "x".repeat(1_000_001)),
            EditorDraft(timestamp = 0L, rawTags = "т".repeat(6_001)),
            EditorDraft(timestamp = 0L, mood = 0),
            EditorDraft(timestamp = 0L, mood = 6),
        )) assertThrows(IllegalArgumentException::class.java) { invalid.validated() }
    }
}
