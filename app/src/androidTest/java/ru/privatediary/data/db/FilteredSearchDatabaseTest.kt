package ru.privatediary.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ru.privatediary.data.repository.RoomEntryRepository
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.SearchFilters
import java.security.SecureRandom
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FilteredSearchDatabaseTest {
    @Test fun metadataFilteringHappensBeforeTheOneHundredKeywordResultLimit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "filtered-search-${UUID.randomUUID()}.db"
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        var database: DiaryDatabase? = null
        try {
            database = DbFactory.create(context, key.copyOf(), name)
            val repository = RoomEntryRepository(database)
            val day = LocalDate.of(2026, 11, 20)
            val zone = ZoneId.systemDefault()
            val selected = Entry(timestamp = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli(),
                text = "Прогулка с семьёй у озера", mood = 4, tags = listOf("Семья"))
            val excluded = (1..120).map { index ->
                Entry(timestamp = selected.timestamp + index, text = "Прогулка новая $index", mood = 1, tags = listOf("Работа"))
            }
            repository.importEntries(listOf(selected) + excluded)
            val storedSelected = repository.allEntries().single { it.text == selected.text }
            val unfiltered = repository.searchWords("прогул")
            assertEquals(100, unfiltered.size)
            assertFalse(unfiltered.any { it.entry.id == storedSelected.id })

            val filtered = repository.searchWords("прогул", SearchFilters(from = day, to = day, mood = 4, tag = "СЕМЬЯ"))
            assertEquals(listOf(storedSelected), filtered.map { it.entry })
            assertTrue(filtered.single().fragment.contains("Прогулка"))
            assertTrue(repository.searchWords("прогул", SearchFilters(from = day.plusDays(1))).isEmpty())
            assertTrue(repository.searchWords("прогул", SearchFilters(mood = 5)).isEmpty())
            assertTrue(repository.searchWords("прогул", SearchFilters(tag = "Сем")).isEmpty())
        } finally {
            database?.close()
            key.fill(0)
            context.deleteDatabase(name)
        }
    }
}
