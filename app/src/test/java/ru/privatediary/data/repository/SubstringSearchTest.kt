package ru.privatediary.data.repository

import org.junit.Assert.*
import org.junit.Test
import ru.privatediary.util.WordSearch

class SubstringSearchTest {
    @Test fun russianAndEnglishMiddleFragmentsAreCaseInsensitive() {
        val game = WordSearch.prepare("ИГРА")
        assertTrue(game.matches("выиграл в доту"))
        assertTrue(game.matches("Вечером ПОИГРАЛ в настольную игру"))
        assertFalse(game.matches("поел пиццу"))
        assertTrue(WordSearch.prepare("LAY").matches("We played chess"))
        assertEquals(WordSearch.Match(2, 6), game.rangesIn("выиграл").single())
    }

    @Test fun everyFragmentMustMatchButTextAndTagsMayBeCombined() {
        val query = WordSearch.prepare("ИГР вечер")
        assertTrue(query.matches("Выиграл в доту", listOf("Вечерний отдых")))
        assertFalse(query.matches("Выиграл в доту", listOf("Утро")))
        assertFalse(query.matches("Поел пиццу", listOf("Вечер")))
        assertTrue(WordSearch.prepare("сем").matches("Сходили в парк", listOf("Семья")))
    }

    @Test fun wholeWordEvidenceDoesNotConfuseFoodWithVictoryOrCatWithAnotherWord() {
        assertTrue(WordSearch.prepare("еда").matches("Победа в игре"))
        assertFalse(WordSearch.prepare("еда").matchesWholeWords("Победа в игре"))
        assertFalse(WordSearch.prepare("кот").matchesWholeWords("Некоторые события"))
        assertTrue(WordSearch.prepare("еда").matchesWholeWords("Победа! Потом еда."))
        assertTrue(WordSearch.prepare("кот").matchesWholeWords("Пришёл КОТ!"))
        assertTrue(WordSearch.prepare("еда вечер").matchesWholeWords("Еда!", listOf("Вечер")))
        assertFalse(WordSearch.prepare("игра").matchesWholeWords("Выиграл в доту"))
        assertFalse(WordSearch.prepare("игра").matchesWholeWords("Поиграл вечером"))
    }

    @Test fun compatibilityNormalizationAndCombiningAccentsKeepCorrectSourceOffsets() {
        assertTrue(WordSearch.prepare("café").matches("CAFE\u0301"))
        assertTrue(WordSearch.prepare("ABC").matches("ａｂｃ"))
        val ligature = WordSearch.prepare("fi").rangesIn("xﬁy").single()
        assertEquals(WordSearch.Match(1, 2), ligature)
        val accent = WordSearch.prepare("É").rangesIn("Cafe\u0301").single()
        assertEquals(WordSearch.Match(3, 5), accent)
    }

    @Test fun sqlWildcardsAndOperatorsAreOrdinaryLiteralText() {
        assertFalse(WordSearch.prepare("%").matches("Обычная запись"))
        assertTrue(WordSearch.prepare("%").matches("Скидка 20%"))
        assertFalse(WordSearch.prepare("_").matches("anything"))
        assertTrue(WordSearch.prepare("_").matches("entry_name"))
        assertFalse(WordSearch.prepare("озеро OR работа").matches("озеро работа"))
        assertTrue(WordSearch.prepare("C++").matches("Learning c++"))
        assertFalse(WordSearch.prepare("C++").matches("Learning c"))
        assertTrue(WordSearch.prepare("  \n\t ").isEmpty)
        assertFalse(WordSearch.prepare("").matches("anything"))
    }

    @Test fun lateMatchingTextIsVisibleAndFragmentsNeverSplitSurrogates() {
        val source = "😀".repeat(300) + "здесь выиграл в доту" + "😀".repeat(300)
        val fragment = WordSearch.prepare("игра").fragment(source)
        assertTrue(fragment.contains("выиграл"))
        assertTrue(fragment.startsWith("…"))
        assertTrue(fragment.endsWith("…"))
        for (i in fragment.indices) {
            if (Character.isHighSurrogate(fragment[i])) assertTrue(i + 1 < fragment.length && Character.isLowSurrogate(fragment[i + 1]))
            if (Character.isLowSurrogate(fragment[i])) assertTrue(i > 0 && Character.isHighSurrogate(fragment[i - 1]))
        }
        assertTrue(WordSearch.prepare("игра").rangesIn(fragment).isNotEmpty())
    }

    @Test fun aLateMatchStillCentersCorrectlyAfterCompatibilityExpansion() {
        val source = "ﬁ".repeat(300) + "поиграл" + "z".repeat(300)
        val fragment = WordSearch.prepare("ИГРА").fragment(source)
        assertTrue(fragment.contains("поиграл"))
        assertEquals("поиграл", WordSearch.prepare("поиграл").fragment("поиграл"))
    }
}
