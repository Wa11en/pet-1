@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.privatediary.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.privatediary.R
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.SearchResult
import ru.privatediary.ui.DiaryActions
import ru.privatediary.ui.DiaryUiState
import ru.privatediary.ui.SearchMode
import ru.privatediary.ui.theme.DiaryStyle
import ru.privatediary.util.WordSearch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private enum class Page { JOURNAL, SEARCH, SETTINGS }

@Composable
fun DiaryScreens(state: DiaryUiState, actions: DiaryActions) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        when {
            state.loading -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) {
                DiaryEmblem(Modifier.size(88.dp))
                Spacer(Modifier.height(24.dp))
                Text(stringResource(R.string.design_opening), style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(20.dp))
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            !state.onboarded -> SetupScreen(state, actions)
            !state.unlocked -> LockScreen(state, actions)
            else -> UnlockedScreens(state, actions)
        }
        state.errorMessage?.let { message ->
            AlertDialog(onDismissRequest = actions.onDismissError,
                icon = { Icon(Icons.Outlined.Info, null) },
                title = { Text(stringResource(R.string.notice)) }, text = { Text(message) },
                confirmButton = { TextButton(onClick = actions.onDismissError) { Text(stringResource(R.string.ok)) } })
        }
    }
}

@Composable
private fun UnlockedScreens(state: DiaryUiState, actions: DiaryActions) {
    var page by remember { mutableStateOf(Page.JOURNAL) }
    var creationDraftPrompt by remember { mutableStateOf<ru.privatediary.domain.models.EditorDraft?>(null) }
    var favoritesOnly by remember { mutableStateOf(false) }
    var selectedDate by remember { mutableStateOf<LocalDate?>(null) }
    val journalListState = rememberLazyListState()
    LaunchedEffect(selectedDate, favoritesOnly) { journalListState.scrollToItem(0) }
    val searchListState = rememberLazyListState()
    LaunchedEffect(state.query, state.searchFilters) { searchListState.scrollToItem(0) }
    var showTrash by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val movedMessage = stringResource(R.string.trash_moved)
    val undoLabel = stringResource(R.string.trash_undo)
    LaunchedEffect(state.undoRevision) {
        val id = state.undoEntryId ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(movedMessage, undoLabel, withDismissAction = true,
            duration = SnackbarDuration.Long)
        if (result == SnackbarResult.ActionPerformed) actions.onRestoreEntry(id)
        actions.onDismissUndo()
    }
    var deleteEntry by remember { mutableStateOf<Entry?>(null) }
    val focus = LocalFocusManager.current
    val open = actions.onOpenEntry
    val create = {
        val timestamp = selectedDate?.atTime(LocalTime.now())?.atZone(ZoneId.systemDefault())
            ?.toInstant()?.toEpochMilli() ?: System.currentTimeMillis()
        val existing = state.drafts.firstOrNull { it.entryId == 0L &&
            (it.text.isNotBlank() || it.rawTags.isNotBlank() || it.mood != null) }
        favoritesOnly = false
        if (existing != null && localDate(existing.timestamp) != localDate(timestamp)) creationDraftPrompt = existing
        else open(Entry(timestamp = timestamp, text = ""))
    }
    val editing = state.editorEntry
    val draft = state.editorDraft
    if (editing != null && draft != null) {
        EditorScreen(entry = editing, draft = draft, draftSaving = state.draftSaving, busy = state.busy,
            onClose = actions.onCloseEditor, onSave = actions.onSaveEntry, onDelete = { deleteEntry = editing },
            onDraftChanged = actions.onDraftChanged, onDiscardDraft = actions.onDiscardDraft,
            focusSearchMatch = state.editorFocusMatch, onToggleFavorite = { actions.onToggleFavorite(editing.id) })
    } else if (showTrash) {
        TrashScreen(state.trash, state.busy, onBack = { showTrash = false },
            onRestore = actions.onRestoreEntry, onDeleteForever = actions.onDeleteForever)
    } else {
        BackHandler(enabled = page != Page.JOURNAL) { focus.clearFocus(); page = Page.JOURNAL }
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { PageHeader(page, actions.onLock) },
            bottomBar = { DiaryNavigation(page) { focus.clearFocus(); page = it } },
            snackbarHost = { SnackbarHost(snackbar) },
            floatingActionButton = {
                if (page == Page.JOURNAL && state.entries.isNotEmpty()) ExtendedFloatingActionButton(
                    onClick = create, containerColor = DiaryStyle.colors.cover, contentColor = DiaryStyle.colors.onCover,
                    shape = RoundedCornerShape(22.dp), icon = { Icon(Icons.Outlined.Edit, null) },
                    text = { Text(stringResource(R.string.new_entry)) })
            },
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (page) {
                    Page.JOURNAL -> JournalScreen(state.entries, open, create, state.drafts, journalListState,
                        selectedDate, { selectedDate = it }, favoritesOnly, { favoritesOnly = it }, actions.onToggleFavorite) { savedDraft ->
                        open(state.entries.firstOrNull { it.id == savedDraft.entryId }
                            ?: Entry(timestamp = savedDraft.timestamp, text = ""))
                    }
                    Page.SEARCH -> SearchScreen(state, actions, searchListState)
                    Page.SETTINGS -> SettingsScreen(state, actions, onOpenTrash = { showTrash = true })
                }
            }
        }
    }
    creationDraftPrompt?.let { existing ->
        AlertDialog(onDismissRequest = { creationDraftPrompt = null },
            title = { Text(stringResource(R.string.resume_draft)) },
            text = { Text(stringResource(R.string.draft_other_date, formatDate(existing.timestamp))) },
            confirmButton = { TextButton(onClick = {
                creationDraftPrompt = null
                selectedDate = localDate(existing.timestamp)
                open(Entry(timestamp = existing.timestamp, text = ""))
            }) { Text(stringResource(R.string.continue_writing)) } },
            dismissButton = { TextButton(onClick = { creationDraftPrompt = null }) { Text(stringResource(R.string.cancel)) } })
    }
    deleteEntry?.let { entry ->
        AlertDialog(onDismissRequest = { deleteEntry = null },
            icon = { Icon(Icons.Outlined.DeleteOutline, null) },
            title = { Text(stringResource(R.string.delete_title)) }, text = { Text(stringResource(R.string.trash_delete_explanation)) },
            confirmButton = { TextButton(enabled = !state.busy, onClick = { actions.onDeleteEntry(entry.id); deleteEntry = null }) {
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            } }, dismissButton = { TextButton(onClick = { deleteEntry = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun PageHeader(page: Page, onLock: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 24.dp, end = 20.dp, top = 18.dp, bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.app_name).uppercase(Locale.getDefault()),
                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.8.sp), color = MaterialTheme.colorScheme.primary)
            Text(stringResource(when (page) {
                Page.JOURNAL -> R.string.design_journal_title; Page.SEARCH -> R.string.search; Page.SETTINGS -> R.string.settings
            }), style = MaterialTheme.typography.headlineLarge)
            val subtitle = when (page) {
                Page.JOURNAL -> LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault()))
                    .replaceFirstChar { it.titlecase(Locale.getDefault()) }
                Page.SEARCH -> stringResource(R.string.design_search_subtitle)
                Page.SETTINGS -> stringResource(R.string.design_settings_subtitle)
            }
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        FilledTonalIconButton(onClick = onLock, modifier = Modifier.size(48.dp), shape = CircleShape,
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.primary)) {
            Icon(Icons.Outlined.Lock, stringResource(R.string.lock_now), Modifier.size(21.dp))
        }
    }
}

@Composable
private fun DiaryNavigation(page: Page, onPage: (Page) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerLowest,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)), shadowElevation = 6.dp) {
            NavigationBar(containerColor = Color.Transparent, tonalElevation = 0.dp, windowInsets = WindowInsets(0, 0, 0, 0)) {
                listOf(Triple(Page.JOURNAL, Icons.AutoMirrored.Outlined.MenuBook, R.string.journal),
                    Triple(Page.SEARCH, Icons.Outlined.Search, R.string.search),
                    Triple(Page.SETTINGS, Icons.Outlined.Tune, R.string.settings)).forEach { (destination, icon, label) ->
                    NavigationBarItem(selected = page == destination, onClick = { onPage(destination) },
                        icon = { Icon(icon, null, Modifier.size(23.dp)) }, label = { Text(stringResource(label), maxLines = 2, textAlign = TextAlign.Center) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant))
                }
            }
        }
    }
}

@Composable
private fun JournalScreen(entries: List<Entry>, onOpen: (Entry) -> Unit, onCreate: () -> Unit,
    drafts: List<ru.privatediary.domain.models.EditorDraft>, listState: LazyListState,
    selectedDate: LocalDate?, onSelectDate: (LocalDate?) -> Unit, favoritesOnly: Boolean,
    onFavoritesOnly: (Boolean) -> Unit, onToggleFavorite: (Long) -> Unit, onResumeDraft: (ru.privatediary.domain.models.EditorDraft) -> Unit) {
    val today = LocalDate.now()
    val scopedEntries = remember(entries, favoritesOnly) { entries.filter { !favoritesOnly || it.isFavorite } }
    val onThisDay = remember(scopedEntries, today) { scopedEntries.filter { val date = localDate(it.timestamp)
        date.year < today.year && date.month == today.month && date.dayOfMonth == today.dayOfMonth } }
    val visibleEntries = remember(scopedEntries, selectedDate) {
        scopedEntries.filter { selectedDate == null || localDate(it.timestamp) == selectedDate }
    }
    val groups = remember(visibleEntries) {
        visibleEntries.sortedByDescending { it.timestamp }.groupBy { localDate(it.timestamp) }
    }
    LazyColumn(state = listState, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 100.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item(key = "invitation") { WritingInvitation(entries.isEmpty(), onCreate) }
        item(key = "calendar") { CalendarPanel(scopedEntries, selectedDate, onSelectDate) }
        item(key = "favorites") {
            FilterChip(selected = favoritesOnly, onClick = { onFavoritesOnly(!favoritesOnly) },
                label = { Text(stringResource(R.string.favorite_filter)) },
                leadingIcon = { Icon(Icons.Outlined.Star, null, Modifier.size(18.dp)) })
        }
        items(drafts.filter { draft ->
            val original = entries.firstOrNull { it.id == draft.entryId }
            if (draft.entryId == 0L) draft.text.isNotBlank() || draft.rawTags.isNotBlank() || draft.mood != null
            else original != null && draft.toEntry() != original.copy(isFavorite = false)
        }, key = { "draft-${it.entryId}" }) { draft ->
            OutlinedCard(onClick = { onResumeDraft(draft) }, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Outlined.EditNote, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.resume_draft), style = MaterialTheme.typography.titleSmall)
                        Text(draft.text.ifBlank { formatDateTime(draft.timestamp) }, maxLines = 2,
                            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    }
                    Icon(Icons.Outlined.ChevronRight, null)
                }
            }
        }
        if (visibleEntries.isEmpty()) item {
            Row(Modifier.padding(horizontal = 8.dp, vertical = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Icon(Icons.Outlined.AutoStories, null, Modifier.padding(top = 4.dp).size(24.dp), tint = DiaryStyle.colors.accent)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(when { favoritesOnly -> R.string.favorite_empty; selectedDate != null -> R.string.calendar_day_empty; else -> R.string.empty_journal }),
                        style = MaterialTheme.typography.titleLarge)
                    if (favoritesOnly) TextButton(onClick = { onFavoritesOnly(false); onSelectDate(null) }) {
                        Text(stringResource(R.string.favorite_show_all))
                    }
                    else if (selectedDate == null) Text(stringResource(R.string.design_empty_hint), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else TextButton(onClick = onCreate) { Text(stringResource(R.string.new_entry)) }
                }
            }
        } else {
            item { SectionCaption(stringResource(R.string.design_your_entries),
                pluralStringResource(R.plurals.design_entry_count, visibleEntries.size, visibleEntries.size)) }
        }
        if (selectedDate == null && onThisDay.isNotEmpty()) {
            item { Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.History, null, Modifier.size(19.dp), tint = DiaryStyle.colors.accent)
                Text(stringResource(R.string.on_this_day), style = MaterialTheme.typography.titleLarge)
            } }
            items(onThisDay, key = { "memory-${it.id}" }) { EntryCard(it, onOpen, onToggleFavorite, showDate = true) }
        }
        groups.forEach { (date, daily) ->
            item(key = "date-$date") {
                Text(if (date == today) stringResource(R.string.design_today) else date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)),
                    Modifier.padding(top = 16.dp, bottom = 2.dp), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items(daily, key = { "entry-${it.id}" }) { EntryCard(it, onOpen, onToggleFavorite) }
        }
    }
}

@Composable
private fun WritingInvitation(empty: Boolean, onCreate: () -> Unit) {
    val colors = DiaryStyle.colors
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp))
        .background(Brush.linearGradient(listOf(colors.cover, colors.cover.copy(red = (colors.cover.red + 0.025f).coerceAtMost(1f)))))) {
        Canvas(Modifier.matchParentSize()) {
            drawCircle(colors.onCover.copy(alpha = 0.045f), size.width * 0.49f, Offset(size.width * 1.02f, size.height * 0.09f))
            drawCircle(colors.onCover.copy(alpha = 0.065f), size.width * 0.33f, Offset(size.width * 0.98f, size.height * 0.13f), style = Stroke(1.dp.toPx()))
        }
        if (!empty) {
            Row(Modifier.padding(22.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.design_private_badge), color = colors.mutedCover,
                        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.5.sp))
                    Text(stringResource(R.string.design_invitation_today).replace('\n', ' '),
                        style = MaterialTheme.typography.headlineSmall, color = colors.onCover)
                }
                FilledIconButton(onClick = onCreate, modifier = Modifier.size(52.dp), shape = RoundedCornerShape(18.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = colors.onCover, contentColor = colors.cover)) {
                    Icon(Icons.Outlined.Edit, stringResource(R.string.new_entry), Modifier.size(22.dp))
                }
            }
        } else Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Icon(Icons.Outlined.VerifiedUser, null, Modifier.size(15.dp), tint = colors.mutedCover)
                Text(stringResource(R.string.design_private_badge), color = colors.mutedCover,
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.9.sp))
            }
            Text(stringResource(if (empty) R.string.design_invitation_first else R.string.design_invitation_today),
                style = MaterialTheme.typography.displaySmall, color = colors.onCover)
            Text(stringResource(R.string.design_invitation_caption), style = MaterialTheme.typography.bodyMedium, color = colors.mutedCover)
            Button(onClick = onCreate, modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 52.dp),
                shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = colors.onCover, contentColor = colors.cover),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                Text(stringResource(if (empty) R.string.design_first_entry else R.string.new_entry), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun SectionCaption(title: String, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 1.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(Locale.getDefault()), Modifier.weight(1f),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.3.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        trailing?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun EntryCard(entry: Entry, onOpen: (Entry) -> Unit, onToggleFavorite: (Long) -> Unit, showDate: Boolean = false) {
    val palette = MaterialTheme.colorScheme
    Card(onClick = { onOpen(entry) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = if (showDate) palette.tertiaryContainer.copy(alpha = 0.38f) else palette.surfaceContainerLowest),
        border = BorderStroke(1.dp, if (showDate) palette.tertiary.copy(alpha = 0.15f) else palette.outlineVariant.copy(alpha = 0.65f))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(if (showDate) Icons.Outlined.History else Icons.Outlined.Schedule, null, Modifier.size(15.dp), tint = palette.onSurfaceVariant)
                Text(if (showDate) formatDateTime(entry.timestamp) else formatTime(entry.timestamp), style = MaterialTheme.typography.labelMedium,
                    color = palette.onSurfaceVariant, modifier = Modifier.weight(1f))
                entry.mood?.let { mood ->
                    val moodText = stringResource(R.string.mood_score, mood)
                    Row(Modifier.semantics(mergeDescendants = true) { contentDescription = moodText },
                        horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(moodIcon(mood), null, Modifier.size(20.dp), tint = if (mood >= 4) palette.primary else palette.tertiary)
                        Text("$mood/5", style = MaterialTheme.typography.labelMedium, color = palette.onSurfaceVariant)
                    }
                }
                FavoriteButton(entry, onToggle = onToggleFavorite)
            }
            Text(entry.text, style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
            if (entry.tags.isNotEmpty()) TagRow(entry.tags)
        }
    }
}

private fun moodIcon(mood: Int): ImageVector = when (mood) {
    1 -> Icons.Outlined.SentimentVeryDissatisfied
    2 -> Icons.Outlined.SentimentDissatisfied
    3 -> Icons.Outlined.SentimentNeutral
    4 -> Icons.Outlined.SentimentSatisfied
    else -> Icons.Outlined.SentimentVerySatisfied
}

@Composable
private fun TagRow(tags: List<String>) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tags.take(3).forEach { tag ->
            Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(8.dp)) {
                Text("#$tag", Modifier.padding(horizontal = 9.dp, vertical = 5.dp).widthIn(max = 160.dp),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (tags.size > 3) Text(stringResource(R.string.design_more_tags, tags.size - 3),
            Modifier.padding(horizontal = 5.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SearchScreen(state: DiaryUiState, actions: DiaryActions, listState: LazyListState) {
    val palette = MaterialTheme.colorScheme
    val highlight = palette.primaryContainer
    LazyColumn(state = listState, contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        item {
            OutlinedTextField(state.query, actions.onQueryChanged,
                placeholder = { Text(stringResource(R.string.search_hint), style = MaterialTheme.typography.bodyMedium) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = palette.surfaceContainerLowest,
                    focusedContainerColor = palette.surfaceContainerLowest, unfocusedBorderColor = palette.outlineVariant),
                leadingIcon = { Icon(Icons.Outlined.Search, null, tint = palette.primary) }, singleLine = true,
                trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { actions.onQueryChanged("") }) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.clear_search))
                } })
        }
        item {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                SegmentedButton(selected = state.searchMode == SearchMode.WORDS, onClick = { actions.onSearchModeChanged(SearchMode.WORDS) },
                    shape = SegmentedButtonDefaults.itemShape(0, 2)) { Text(stringResource(R.string.by_words)) }
                SegmentedButton(selected = state.searchMode == SearchMode.MEANING, onClick = { actions.onSearchModeChanged(SearchMode.MEANING) },
                    shape = SegmentedButtonDefaults.itemShape(1, 2)) { Text(stringResource(R.string.by_meaning)) }
            }
        }
        item {
            SearchFiltersPanel(state.searchFilters, state.entries.flatMap { it.tags }.distinct().sorted(),
                enabled = !state.busy, onChange = actions.onSearchFiltersChanged)
        }
        if (state.searchMode == SearchMode.MEANING) item {
            Surface(shape = RoundedCornerShape(18.dp), color = palette.surfaceContainerHigh) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(if (state.semanticAvailable) Icons.Outlined.AutoAwesome else Icons.Outlined.Info, null,
                        Modifier.padding(top = 2.dp).size(20.dp), tint = palette.primary)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(when {
                            state.indexing && !state.semanticAvailable -> R.string.semantic_preparing
                            state.semanticAvailable -> R.string.semantic_ready
                            else -> R.string.semantic_missing
                        }),
                            style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                        if (state.semanticAvailable) Text(stringResource(R.string.semantic_score_explanation),
                            style = MaterialTheme.typography.bodySmall, color = palette.onSurfaceVariant)
                        if (state.indexing) Text(stringResource(R.string.indexing), style = MaterialTheme.typography.labelSmall, color = palette.primary)
                    }
                }
            }
        }
        if (state.query.isBlank() && state.searchFilters.isEmpty) item { SearchSuggestion() }
        else if (state.searching) item {
            Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.semantic_searching), style = MaterialTheme.typography.bodyMedium)
            }
        }
        else if (state.results.isEmpty() && !state.busy) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Outlined.SearchOff, null, Modifier.size(38.dp), tint = palette.onSurfaceVariant)
                Text(stringResource(if (state.searchMode == SearchMode.MEANING && state.semanticAvailable)
                    R.string.semantic_no_results else R.string.no_results),
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    color = palette.onSurfaceVariant)
            }
        }
        if (state.results.isNotEmpty()) item { SectionCaption(stringResource(R.string.design_results),
            pluralStringResource(R.plurals.design_entry_count, state.results.size, state.results.size)) }
        items(state.results, key = { it.entry.id }) { result -> SearchResultCard(result, state.query, highlight, actions.onOpenSearchResult, actions.onToggleFavorite) }
    }
}

@Composable
private fun SearchSuggestion() {
    val palette = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(top = 30.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Surface(shape = RoundedCornerShape(26.dp), color = palette.primaryContainer.copy(alpha = 0.7f)) {
            Icon(Icons.AutoMirrored.Outlined.ManageSearch, null, Modifier.padding(24.dp).size(34.dp), tint = palette.primary)
        }
        Text(stringResource(R.string.design_search_empty_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(stringResource(R.string.search_examples), style = MaterialTheme.typography.bodyMedium,
            color = palette.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 12.dp))
    }
}

@Composable
private fun SearchResultCard(result: SearchResult, query: String, highlight: Color, onOpen: (SearchResult) -> Unit, onToggleFavorite: (Long) -> Unit) {
    Card(onClick = { onOpen(result) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Outlined.CalendarToday, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatDateTime(result.entry.timestamp), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                FavoriteButton(result.entry, onToggle = onToggleFavorite)
            }
            Text(highlightFragment(result.fragment, query, result.score != null, highlight),
                style = MaterialTheme.typography.bodyLarge, maxLines = 8, overflow = TextOverflow.Ellipsis)
            if (result.entry.tags.isNotEmpty()) Text(
                highlightFragment(result.entry.tags.joinToString("  ") { "#$it" }, query, false, highlight),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            result.score?.let { score ->
                Text(stringResource(R.string.similarity, score),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

private fun highlightFragment(fragment: String, query: String, semantic: Boolean, color: Color) = buildAnnotatedString {
    append(fragment)
    if (semantic) {
        if (fragment.isNotEmpty()) addStyle(SpanStyle(background = color), 0, fragment.length)
    } else {
        for (match in WordSearch.prepare(query).rangesIn(fragment)) {
            addStyle(SpanStyle(background = color, fontWeight = FontWeight.Medium), match.start, match.endExclusive)
        }
    }
}
