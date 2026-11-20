package ru.privatediary.util

import org.junit.Assert.*
import org.junit.Test

class SearchFocusTest {
    @Test fun keywordFocusUsesTheResultContextRatherThanAnEarlierOccurrence() {
        val text = "игра утром. Прогулка. Вечером выиграл в доту."
        val focus = SearchFocus.selection(text, "…Вечером выиграл в доту.", "игра", semantic = false)!!
        assertEquals(text.indexOf("играл"), focus.start)
        assertEquals("игра", text.substring(focus.start, focus.endExclusive))
    }

    @Test fun ftsOmittedPassagesStillFindARealMatchingSection() {
        val text = "Первый абзац. " + "длинный текст ".repeat(30) + "Семейная прогулка у озера закончилась поздно."
        val fragment = "Первый абзац. … Семейная прогулка у озера закончилась поздно."
        val focus = SearchFocus.selection(text, fragment, "ОЗЕР", semantic = false)!!
        assertEquals(text.indexOf("озера"), focus.start)
        assertEquals("озер", text.substring(focus.start, focus.endExclusive))
    }

    @Test fun semanticFocusSelectsTheFragmentWithoutNeedingTheQueryWord() {
        val text = "Утром работал. Вечером сидел у озера и слушал птиц. Потом домой."
        val fragment = "Вечером сидел у озера и слушал птиц."
        val focus = SearchFocus.selection(text, fragment, "спокойствие", semantic = true)!!
        assertEquals(fragment, text.substring(focus.start, focus.endExclusive))
    }

    @Test fun recoveredDraftUsesItsCurrentOffsetsAndDoesNotPretendADeletedFragmentExists() {
        val fragment = "Потом поиграл в контру."
        val text = "Добавлено в черновик 🌿. Начало. $fragment"
        val focus = SearchFocus.selection(text, fragment, "игра", semantic = false)!!
        assertEquals(text.indexOf("играл"), focus.start)
        assertEquals("игра", text.substring(focus.start, focus.endExclusive))
        assertNull(SearchFocus.selection("Черновик полностью переписан", fragment, "спокойствие", semantic = true))
    }

    @Test fun unicodeNormalizationKeepsSourceUtf16RangesAndSurrogatePairsIntact() {
        val text = "🌿 Тёплый день, cafe\u0301 и ＷＯＲＫ."
        val cafe = SearchFocus.selection(text, text, "CAFÉ", semantic = false)!!
        assertEquals("cafe\u0301", text.substring(cafe.start, cafe.endExclusive))
        val work = SearchFocus.selection(text, text, "work", semantic = false)!!
        assertEquals("ＷＯＲＫ", text.substring(work.start, work.endExclusive))
        val emoji = SearchFocus.selection(text, "🌿", "nature", semantic = true)!!
        assertEquals(SearchFocus.Selection(0, 2), emoji)
    }

    @Test fun tagOnlyHitsPlaceTheCursorAtTheirPreviewAndEmptyTextHasNoFocus() {
        val text = "Вечер у озера"
        assertEquals(SearchFocus.Selection(0, 0), SearchFocus.selection(text, text, "семья", semantic = false))
        assertNull(SearchFocus.selection("", "", "игра", semantic = false))
    }
}
