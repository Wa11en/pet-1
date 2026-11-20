package ru.privatediary.ui

import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.EditorDraft
import ru.privatediary.domain.models.TrashedEntry
import ru.privatediary.domain.models.SearchFilters
import ru.privatediary.domain.models.SearchResult

enum class SearchMode { WORDS, MEANING }

data class DiaryUiState(
    val loading: Boolean = true,
    val onboarded: Boolean = false,
    val unlocked: Boolean = false,
    val busy: Boolean = false,
    val entries: List<Entry> = emptyList(),
    val results: List<SearchResult> = emptyList(),
    val searching: Boolean = false,
    val query: String = "",
    val searchMode: SearchMode = SearchMode.WORDS,
    val biometricAvailable: Boolean = false,
    val biometricsEnabled: Boolean = false,
    val timeoutSeconds: Int = 60,
    val semanticAvailable: Boolean = false,
    val indexing: Boolean = false,
    val errorMessage: String? = null,
    val lockRemainingSeconds: Long = 0,
    val savedRevision: Long = 0,
    val selectedLanguage: String = "ru",
    val drafts: List<EditorDraft> = emptyList(),
    val editorEntry: Entry? = null,
    val editorDraft: EditorDraft? = null,
    val draftSaving: Boolean = false,
    val trash: List<TrashedEntry> = emptyList(),
    val undoEntryId: Long? = null,
    val undoRevision: Long = 0,
    val searchFilters: SearchFilters = SearchFilters(),
    val editorFocusMatch: Boolean = false,
    val dailyReminderEnabled: Boolean = false,
    val dailyReminderMinutes: Int = 20 * 60,
    val backupReminderEnabled: Boolean = false,
)

data class DiaryActions(
    val onCreatePin: (String, Boolean) -> Unit,
    val onUnlock: (String) -> Unit,
    val onBiometric: () -> Unit,
    val onSaveEntry: (Entry) -> Unit,
    val onDeleteEntry: (Long) -> Unit,
    val onQueryChanged: (String) -> Unit,
    val onSearchModeChanged: (SearchMode) -> Unit,
    val onTimeoutChanged: (Int) -> Unit,
    val onBiometricsChanged: (Boolean) -> Unit,
    val onLanguageChanged: (String) -> Unit,
    val onImport: () -> Unit,
    val onExportMarkdown: () -> Unit,
    val onBackup: (String) -> Unit,
    val onRestore: (String) -> Unit,
    val onDismissError: () -> Unit,
    val onLock: () -> Unit,
    val onOpenEntry: (Entry) -> Unit,
    val onCloseEditor: () -> Unit,
    val onDraftChanged: (EditorDraft) -> Unit,
    val onDiscardDraft: () -> Unit,
    val onRestoreEntry: (Long) -> Unit,
    val onDeleteForever: (Long) -> Unit,
    val onDismissUndo: () -> Unit,
    val onSearchFiltersChanged: (SearchFilters) -> Unit,
    val onOpenSearchResult: (SearchResult) -> Unit,
    val onToggleFavorite: (Long) -> Unit,
    val onDailyReminderChanged: (Boolean) -> Unit,
    val onReminderTimeChanged: (Int) -> Unit,
    val onBackupReminderChanged: (Boolean) -> Unit,
)
