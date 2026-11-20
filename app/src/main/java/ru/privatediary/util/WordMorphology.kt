package ru.privatediary.util

import java.text.Normalizer
import java.util.Locale
import org.tartarus.snowball.ext.englishStemmer
import org.tartarus.snowball.ext.russianStemmer

/** Word-form evidence for semantic search, rather than arbitrary character substrings. */
object WordMorphology {
    private val word = Regex("[\\p{L}\\p{N}]+")
    private val prefixes = listOf("пере", "пред", "под", "при", "про", "раз", "рас", "вы", "по", "за", "от", "до", "на", "об", "в", "с", "у")
    private val verbEndings = listOf("л", "ла", "ло", "ли", "ть", "ти", "чь", "ешь", "ете", "ите", "ют", "ут", "ет", "ит", "ем", "им", "йте")

    class PreparedQuery internal constructor(private val roots: List<Set<String>>) {
        fun matches(text: String): Boolean {
            if (roots.isEmpty() || roots.any { it.isEmpty() }) return false
            val source = word.findAll(normalize(text)).flatMap { rootsFor(it.value).asSequence() }.toSet()
            return roots.all { alternatives -> alternatives.any { it in source } }
        }
    }

    fun prepare(query: String): PreparedQuery {
        val terms = WordSearch.prepare(query).terms
        if (terms.isEmpty() || terms.any { !word.matches(it) }) return PreparedQuery(emptyList())
        return PreparedQuery(terms.map(::rootsFor))
    }

    private fun normalize(value: String) = Normalizer.normalize(value, Normalizer.Form.NFKC).lowercase(Locale.ROOT)

    private fun rootsFor(value: String): Set<String> {
        val russian = value.any { it in 'а'..'я' || it == 'ё' }
        fun stem(text: String): String {
            val algorithm = if (russian) russianStemmer() else englishStemmer()
            algorithm.setCurrent(text)
            algorithm.stem()
            return algorithm.current
        }
        val base = stem(value)
        val result = mutableSetOf(base)
        val ending = value.removeSuffix("ся").removeSuffix("сь")
        if (russian && verbEndings.any { ending.endsWith(it) }) {
            // Russian verbal prefixes remain after Snowball: выиграл -> выигра.
            // A second stemming pass on the unprefixed form links it to игра -> игр.
            result += stem(base)
            for (prefix in prefixes) if (base.startsWith(prefix)) result += stem(base.removePrefix(prefix))
        }
        return result.filterTo(mutableSetOf()) { it.length >= 3 }
    }
}
