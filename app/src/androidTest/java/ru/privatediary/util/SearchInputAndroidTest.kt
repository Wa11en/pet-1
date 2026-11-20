package ru.privatediary.util

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against Android's regex and grapheme implementations, rather than the host JVM. */
@RunWith(AndroidJUnit4::class)
class SearchInputAndroidTest {
    @Test fun typingTheFirstCharactersProducesValidHighlightRanges() {
        val entries = listOf("🙂 Поиграл в контру", "🎮 выиграл в доту")
        for (input in listOf("и", "иг", "игр", "игра")) {
            val query = WordSearch.prepare(input)
            assertEquals(listOf(input), query.terms)
            for (entry in entries) {
                assertTrue("$input must match $entry", query.matches(entry))
                val fragment = query.fragment(entry)
                val ranges = query.rangesIn(fragment)
                assertTrue("$input must have a highlight in $fragment", ranges.isNotEmpty())
                for (range in ranges) {
                    assertTrue(range.start >= 0)
                    assertTrue(range.endExclusive > range.start)
                    assertTrue(range.endExclusive <= fragment.length)
                    assertFalse(Character.isLowSurrogate(fragment[range.start]))
                    if (range.endExclusive < fragment.length) {
                        assertFalse(Character.isLowSurrogate(fragment[range.endExclusive]))
                    }
                    assertEquals(input, fragment.substring(range.start, range.endExclusive).lowercase())
                }
            }
        }
    }

    @Test fun unicodeSpacesSeparateQueryTermsOnAndroid() {
        val query = WordSearch.prepare("игра\u00A0дота\u0085пицца\u202Fтеги\u2003вечер")
        assertEquals(listOf("игра", "дота", "пицца", "теги", "вечер"), query.terms)
        assertTrue(query.matches("игра дота пицца теги вечер"))
    }

    @Test fun russianGameWordFormsLoadAndMatchOnAndroid() {
        val query = WordMorphology.prepare("игра")
        assertTrue(query.matches("поиграл в контру"))
        assertTrue(query.matches("выиграл в доту"))
        assertFalse(query.matches("поел пиццу"))
    }
}
