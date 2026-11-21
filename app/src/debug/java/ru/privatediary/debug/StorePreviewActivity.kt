package ru.privatediary.debug

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.LocaleList
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import ru.privatediary.domain.models.EditorDraft
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.SearchResult
import ru.privatediary.ui.DiaryActions
import ru.privatediary.ui.DiaryUiState
import ru.privatediary.ui.SearchMode
import ru.privatediary.ui.screens.DiaryScreens
import ru.privatediary.ui.theme.DiaryTheme
import ru.privatediary.util.WordSearch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only renderer for store pictures. It never reads a diary, calls the ViewModel,
 * opens SQLCipher or runs an embedding model. Every displayed entry is a public fixture.
 * MainActivity's FLAG_SECURE is untouched; release builds contain neither this class
 * nor this activity declaration.
 */
class StorePreviewActivity : ComponentActivity() {
    private var preview by mutableStateOf(DiaryUiState())
    val drawnFrames = AtomicLong()

    override fun attachBaseContext(base: Context) {
        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration).apply {
            setLocales(LocaleList(locale))
        }
        super.attachBaseContext(base.createConfigurationContext(configuration))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(intent.action == ACTION_CAPTURE) { "This fixture is only opened by the screenshot test" }
        check(window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE == 0)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val entries = fixtures(languageTag)
        val editor = intent.getStringExtra(EXTRA_SCENE) == "editor"
        val query = if (languageTag == "ru") "озера" else "lake"
        val words = WordSearch.prepare(query)
        preview = DiaryUiState(
            loading = false, onboarded = true, unlocked = true,
            entries = entries, selectedLanguage = languageTag,
            // These are genuine literal substring hits. No synthetic embedding score is shown.
            query = query, searchMode = SearchMode.WORDS,
            results = entries.filter { words.matches(it.text, it.tags) }
                .map { SearchResult(it, words.fragment(it.text), score = null) },
            semanticAvailable = true,
            editorEntry = entries.first().takeIf { editor },
            editorDraft = entries.first().takeIf { editor }?.let(EditorDraft::from),
        )
        window.decorView.viewTreeObserver.addOnDrawListener { drawnFrames.incrementAndGet() }
        setContent { DiaryTheme { DiaryScreens(preview, actions()) } }
    }

    private fun actions() = DiaryActions(
        onCreatePin = { _, _ -> }, onUnlock = {}, onBiometric = {},
        onSaveEntry = { entry -> preview = preview.copy(editorEntry = null, editorDraft = null,
            entries = preview.entries.map { if (it.id == entry.id) entry else it }) },
        onDeleteEntry = { id -> preview = preview.copy(editorEntry = null, editorDraft = null,
            entries = preview.entries.filterNot { it.id == id }) },
        onQueryChanged = { query ->
            val words = WordSearch.prepare(query)
            preview = preview.copy(query = query, results = preview.entries
                .filter { words.matches(it.text, it.tags) }
                .map { SearchResult(it, words.fragment(it.text), score = null) })
        },
        onSearchModeChanged = { preview = preview.copy(searchMode = it) },
        onTimeoutChanged = { preview = preview.copy(timeoutSeconds = it) },
        onBiometricsChanged = {}, onLanguageChanged = {}, onImport = {}, onExportMarkdown = {},
        onBackup = {}, onRestore = {}, onDismissError = {}, onLock = {},
        onOpenEntry = { preview = preview.copy(editorEntry = it, editorDraft = EditorDraft.from(it)) },
        onCloseEditor = { preview = preview.copy(editorEntry = null, editorDraft = null) },
        onDraftChanged = { preview = preview.copy(editorDraft = it) },
        onDiscardDraft = { preview = preview.copy(editorEntry = null, editorDraft = null) },
        onRestoreEntry = {}, onDeleteForever = {}, onDismissUndo = {},
        onSearchFiltersChanged = { preview = preview.copy(searchFilters = it) },
        onOpenSearchResult = { preview = preview.copy(editorEntry = it.entry, editorDraft = EditorDraft.from(it.entry)) },
        onToggleFavorite = { id -> preview = preview.copy(entries = preview.entries.map {
            if (it.id == id) it.copy(isFavorite = !it.isFavorite) else it
        }) },
        onDailyReminderChanged = {}, onReminderTimeChanged = {}, onBackupReminderChanged = {},
    )

    companion object {
        const val ACTION_CAPTURE = "ru.privatediary.debug.CAPTURE_STORE_PREVIEW"
        const val EXTRA_SCENE = "scene"
        @Volatile var languageTag: String = "ru"

        private fun fixtures(language: String): List<Entry> {
            val russian = language == "ru"
            val lake = if (russian)
                "Вечером я сидел на берегу озера, смотрел на воду и слушал птиц. Мысли перестали метаться, дыхание стало ровным, я никуда не торопился."
            else "I sat by the lake at sunset, listened to birds and watched the water. My breathing slowed down, my mind stopped racing and I had nowhere to rush."
            val work = if (russian)
                "Сегодня разобрал сложную задачу. Было приятно заметить, сколько уже умею. После работы оставил время для себя."
            else "I worked through a difficult task today. It felt good to notice how much I have learned. After work, I made time for myself."
            val pause = if (russian)
                "Сделал небольшую паузу: тёплый чай, любимая книга и тихий вечер. Хочется чаще замечать такие простые моменты."
            else "A small pause: warm tea, a favorite book and a quiet evening. I want to notice these simple moments more often."
            fun date(day: Int, hour: Int) = LocalDate.of(2026, 11, day)
                .atTime(LocalTime.of(hour, 40)).toInstant(ZoneOffset.UTC).toEpochMilli()
            return listOf(
                Entry(1, date(21, 18), lake, 5,
                    if (russian) listOf("прогулка", "для себя") else listOf("walk", "me time"), isFavorite = true),
                Entry(2, date(20, 19), work, 4, if (russian) listOf("работа") else listOf("work")),
                Entry(3, date(18, 20), pause, 4, if (russian) listOf("маленькие радости") else listOf("small joys")),
                Entry(4, date(16, 17), work, 3), Entry(5, date(12, 18), pause, 5),
                Entry(6, date(8, 15), lake, 4), Entry(7, date(4, 18), pause, 4),
                Entry(8, date(2, 19), work, 3),
            )
        }
    }
}
