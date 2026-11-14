package ru.privatediary.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** Exercises SQLite FTS4 itself, including Cyrillic prefix semantics; no Android runtime required. */
class FtsQueryTest {
    private fun withDatabase(block: (Connection) -> Unit) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { database ->
            database.createStatement().use {
                it.execute("CREATE VIRTUAL TABLE entry_fts USING fts4(text, tags, tokenize=unicode61)")
            }
            database.prepareStatement("INSERT INTO entry_fts(rowid, text, tags) VALUES (?, ?, ?)").use { insert ->
                listOf(
                    Triple(1L, "Тихая прогулка у озера принесла спокойствие", "природа утро"),
                    Triple(2L, "A peaceful morning by the lake", "nature morning"),
                    Triple(3L, "Рабочая встреча и важные решения", "работа")
                ).forEach { (id, text, tags) ->
                    insert.setLong(1, id)
                    insert.setString(2, text)
                    insert.setString(3, tags)
                    insert.executeUpdate()
                }
            }
            block(database)
        }
    }

    private fun search(database: Connection, input: String): List<Long> {
        val match = FtsQuery.fromUserInput(input)
        if (match.isEmpty()) return emptyList()
        return database.prepareStatement("SELECT rowid FROM entry_fts WHERE entry_fts MATCH ? ORDER BY rowid").use { query ->
            query.setString(1, match)
            query.executeQuery().use { rows -> buildList { while (rows.next()) add(rows.getLong(1)) } }
        }
    }

    @Test fun russianAndEnglishPrefixesUseUnicodeTokenizer() = withDatabase { database ->
        assertEquals(listOf(1L), search(database, "СПОКОЙ"))
        assertEquals(listOf(2L), search(database, "PEACE morn"))
        assertEquals(listOf(1L), search(database, "озер"))
    }

    @Test fun termsMustAllMatchAndTagsAreSearchable() = withDatabase { database ->
        assertEquals(listOf(1L), search(database, "прогулка природа"))
        assertEquals(listOf(2L), search(database, "lake nature"))
        assertTrue(search(database, "прогулка работа").isEmpty())
    }

    @Test fun punctuationIsNotInterpretedAsFtsOrSqlSyntax() = withDatabase { database ->
        assertEquals(listOf(1L), search(database, "\"озера\"!"))
        assertTrue(search(database, "озеро OR работа").isEmpty())
        assertTrue(search(database, "\" OR 1=1; --").isEmpty())
        assertTrue(search(database, "  + - : ( ) \" * ").isEmpty())
        // Table and data still exist after hostile input.
        assertEquals(listOf(3L), search(database, "встреча"))
    }

    @Test fun blankInputHasNoMatchExpression() {
        assertEquals("", FtsQuery.fromUserInput(""))
        assertEquals("", FtsQuery.fromUserInput(" \n\t "))
    }
}
