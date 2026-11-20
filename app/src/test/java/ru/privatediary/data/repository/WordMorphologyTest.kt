package ru.privatediary.data.repository

import org.junit.Assert.*
import org.junit.Test
import ru.privatediary.util.WordMorphology

class WordMorphologyTest {
    @Test fun gameFormsAndRussianVerbalPrefixesShareAWordRoot() {
        for (query in listOf("игра", "игры", "ИГРА")) {
            assertTrue(WordMorphology.prepare(query).matches("поиграл в контру"))
            assertTrue(WordMorphology.prepare(query).matches("выиграл в доту"))
            assertTrue(WordMorphology.prepare(query).matches("поиграли с друзьями"))
            assertFalse(WordMorphology.prepare(query).matches("поел пиццу"))
        }
        assertTrue(WordMorphology.prepare("контра").matches("поиграл в контру"))
        assertTrue(WordMorphology.prepare("дота").matches("выиграл в доту"))
    }

    @Test fun ordinaryInflectionsWorkWithoutTurningSubstringsIntoMeanings() {
        assertTrue(WordMorphology.prepare("кошки").matches("Кошка мурлыкала"))
        assertTrue(WordMorphology.prepare("coding").matches("I coded yesterday"))
        assertTrue(WordMorphology.prepare("games").matches("We played a game"))
        for (text in listOf("победа в доте", "победил в игре")) assertFalse(WordMorphology.prepare("еда").matches(text))
        assertFalse(WordMorphology.prepare("кот").matches("Некоторые события"))
        assertFalse(WordMorphology.prepare("cat").matches("education and catering"))
        assertFalse(WordMorphology.prepare("pain").matches("painting a picture"))
    }

    @Test fun allTermsMustMatchAndProgrammingPunctuationIsNotDiscarded() {
        assertTrue(WordMorphology.prepare("игры дота").matches("выиграл в доту"))
        assertFalse(WordMorphology.prepare("игры дота").matches("поиграл в контру"))
        assertFalse(WordMorphology.prepare("C++").matches("Learning C"))
        assertFalse(WordMorphology.prepare("").matches("anything"))
    }
}
