@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.privatediary.ui.screens

import ru.privatediary.ui.DiaryUiState
import ru.privatediary.ui.DiaryActions
import ru.privatediary.ui.SearchMode

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.privatediary.R
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.SearchResult
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private enum class Page { JOURNAL, SEARCH, SETTINGS }
private data class EditorSession(val entry: Entry)

@Composable
fun DiaryScreens(state: DiaryUiState, actions: DiaryActions) {
    Surface(Modifier.fillMaxSize()) {
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            !state.onboarded -> SetupScreen(state, actions)
            !state.unlocked -> LockScreen(state, actions)
            else -> UnlockedScreens(state, actions)
        }
        state.errorMessage?.let { message ->
            AlertDialog(onDismissRequest = actions.onDismissError,
                title = { Text(stringResource(R.string.notice)) },
                text = { Text(message) },
                confirmButton = { TextButton(onClick = actions.onDismissError) { Text(stringResource(R.string.ok)) } })
        }
    }
}

@Composable
private fun SetupScreen(state: DiaryUiState, actions: DiaryActions) {
    var pin by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var biometrics by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(28.dp))
        Icon(Icons.Outlined.Lock, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.welcome), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.privacy_detail), style = MaterialTheme.typography.bodyLarge)
        Text(stringResource(R.string.pin_explanation), style = MaterialTheme.typography.bodyMedium)
        PinField(pin, { pin = it }, R.string.create_pin, !state.busy)
        PinField(confirmation, { confirmation = it }, R.string.repeat_pin, !state.busy)
        if (confirmation.isNotEmpty() && pin != confirmation) {
            Text(stringResource(R.string.pin_mismatch), color = MaterialTheme.colorScheme.error)
        }
        if (state.biometricAvailable) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = biometrics, onCheckedChange = { biometrics = it }, enabled = !state.busy)
                Text(stringResource(R.string.enable_biometrics))
            }
        }
        Button(onClick = { actions.onCreatePin(pin, biometrics); pin = ""; confirmation = "" },
            enabled = pin.length >= 6 && pin == confirmation && !state.busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.start_diary))
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun LockScreen(state: DiaryUiState, actions: DiaryActions) {
    var pin by remember { mutableStateOf("") }
    val permitted = !state.busy && state.lockRemainingSeconds <= 0
    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(60.dp))
        Icon(Icons.Outlined.Lock, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(R.string.unlock_hint))
        PinField(pin, { pin = it }, R.string.enter_pin, permitted)
        if (state.lockRemainingSeconds > 0) Text(stringResource(R.string.retry_after, state.lockRemainingSeconds))
        Button(onClick = { actions.onUnlock(pin); pin = "" }, enabled = permitted && pin.length >= 6,
            modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.unlock)) }
        if (state.biometricsEnabled && state.biometricAvailable) {
            OutlinedButton(onClick = actions.onBiometric, enabled = permitted, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.unlock_biometric))
            }
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

@Composable
private fun PinField(value: String, onChange: (String) -> Unit, label: Int, enabled: Boolean) {
    OutlinedTextField(value = value, onValueChange = { text -> if (text.length <= 12 && text.all { it in '0'..'9' }) onChange(text) },
        label = { Text(stringResource(label)) }, modifier = Modifier.fillMaxWidth(), singleLine = true,
        visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        enabled = enabled, supportingText = { Text(stringResource(R.string.pin_length)) })
}

@Composable
private fun UnlockedScreens(state: DiaryUiState, actions: DiaryActions) {
    var page by remember { mutableStateOf(Page.JOURNAL) }
    var editor by remember { mutableStateOf<EditorSession?>(null) }
    var deleteEntry by remember { mutableStateOf<Entry?>(null) }
    var initialSaveRevision by remember { mutableLongStateOf(state.savedRevision) }
    LaunchedEffect(state.savedRevision) { if (state.savedRevision != initialSaveRevision) { editor = null; initialSaveRevision = state.savedRevision } }
    val open: (Entry) -> Unit = { editor = EditorSession(it); initialSaveRevision = state.savedRevision }
    val editing = editor
    if (editing != null) {
        EditorScreen(editing.entry, state.busy, onClose = { editor = null },
            onSave = actions.onSaveEntry, onDelete = { deleteEntry = editing.entry })
    } else {
        Scaffold(topBar = { TopAppBar(title = { Text(stringResource(when(page) {
            Page.JOURNAL -> R.string.journal; Page.SEARCH -> R.string.search; Page.SETTINGS -> R.string.settings
        })) }, actions = { IconButton(onClick = actions.onLock) { Icon(Icons.Outlined.Lock, stringResource(R.string.lock_now)) } }) },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(selected = page == Page.JOURNAL, onClick = { page = Page.JOURNAL },
                        icon = { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) }, label = { Text(stringResource(R.string.journal)) })
                    NavigationBarItem(selected = page == Page.SEARCH, onClick = { page = Page.SEARCH },
                        icon = { Icon(Icons.Outlined.Search, null) }, label = { Text(stringResource(R.string.search)) })
                    NavigationBarItem(selected = page == Page.SETTINGS, onClick = { page = Page.SETTINGS },
                        icon = { Icon(Icons.Outlined.Settings, null) }, label = { Text(stringResource(R.string.settings)) })
                }
            }, floatingActionButton = {
                if (page == Page.JOURNAL) FloatingActionButton(onClick = { open(Entry(timestamp = System.currentTimeMillis(), text = "")) }) {
                    Icon(Icons.Outlined.Add, stringResource(R.string.new_entry))
                }
            }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                when (page) {
                    Page.JOURNAL -> JournalScreen(state.entries, open)
                    Page.SEARCH -> SearchScreen(state, actions, open)
                    Page.SETTINGS -> SettingsScreen(state, actions)
                }
            }
        }
    }
    deleteEntry?.let { entry ->
        AlertDialog(onDismissRequest = { deleteEntry = null }, title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_explanation)) },
            confirmButton = { TextButton(onClick = { actions.onDeleteEntry(entry.id); deleteEntry = null; editor = null }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deleteEntry = null }) { Text(stringResource(R.string.cancel)) } })
    }
}

@Composable
private fun JournalScreen(entries: List<Entry>, onOpen: (Entry) -> Unit) {
    val today = LocalDate.now()
    val onThisDay = entries.filter { val date = localDate(it.timestamp); date.year < today.year && date.month == today.month && date.dayOfMonth == today.dayOfMonth }
    val groups = entries.sortedByDescending { it.timestamp }.groupBy { localDate(it.timestamp) }
    if (entries.isEmpty()) {
        EmptyState(Icons.AutoMirrored.Outlined.MenuBook, R.string.empty_journal, R.string.empty_journal_hint)
    } else {
        LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (onThisDay.isNotEmpty()) {
                item { Text(stringResource(R.string.on_this_day), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary) }
                items(onThisDay, key = { "memory-${it.id}" }) { EntryCard(it, onOpen, showDate = true) }
            }
            groups.forEach { (date, daily) ->
                item(key = "date-$date") { Text(date.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG)),
                    Modifier.padding(top = 12.dp, bottom = 2.dp), style = MaterialTheme.typography.titleMedium) }
                items(daily, key = { "entry-${it.id}" }) { EntryCard(it, onOpen) }
            }
        }
    }
}

@Composable
private fun EntryCard(entry: Entry, onOpen: (Entry) -> Unit, showDate: Boolean = false) {
    Card(onClick = { onOpen(entry) }, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(if (showDate) formatDateTime(entry.timestamp) else formatTime(entry.timestamp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                entry.mood?.let { Text(stringResource(R.string.mood_score, it), style = MaterialTheme.typography.labelMedium) }
            }
            Text(entry.text, style = MaterialTheme.typography.bodyLarge, maxLines = 5, overflow = TextOverflow.Ellipsis)
            if (entry.tags.isNotEmpty()) Text(entry.tags.joinToString("  ") { "#$it" }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SearchScreen(state: DiaryUiState, actions: DiaryActions, onOpen: (Entry) -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(state.query, actions.onQueryChanged, label = { Text(stringResource(R.string.search_hint)) },
            modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true,
            trailingIcon = { if (state.query.isNotEmpty()) IconButton(onClick = { actions.onQueryChanged("") }) { Icon(Icons.Outlined.Close, stringResource(R.string.clear_search)) } })
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            SegmentedButton(selected = state.searchMode == SearchMode.WORDS, onClick = { actions.onSearchModeChanged(SearchMode.WORDS) }, shape = SegmentedButtonDefaults.itemShape(0,2)) { Text(stringResource(R.string.by_words)) }
            SegmentedButton(selected = state.searchMode == SearchMode.MEANING, onClick = { actions.onSearchModeChanged(SearchMode.MEANING) }, shape = SegmentedButtonDefaults.itemShape(1,2)) { Text(stringResource(R.string.by_meaning)) }
        }
        if (state.searchMode == SearchMode.MEANING) {
            Text(stringResource(if (state.semanticAvailable) R.string.semantic_ready else R.string.semantic_missing), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state.indexing) Text(stringResource(R.string.indexing), style = MaterialTheme.typography.bodySmall)
        }
        if (state.query.isBlank()) Text(stringResource(R.string.search_examples), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 24.dp))
        else if (state.results.isEmpty() && !state.busy) Text(stringResource(R.string.no_results), modifier = Modifier.padding(top = 24.dp))
        LazyColumn(contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(state.results, key = { it.entry.id }) { result -> SearchResultCard(result, state.query, onOpen) }
        }
    }
}

@Composable
private fun SearchResultCard(result: SearchResult, query: String, onOpen: (Entry) -> Unit) {
    Card(onClick = { onOpen(result.entry) }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatDateTime(result.entry.timestamp), style = MaterialTheme.typography.labelMedium)
                result.score?.let { Text(stringResource(R.string.similarity, (it.coerceIn(0f,1f) * 100).toInt()), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
            }
            val highlight = MaterialTheme.colorScheme.secondaryContainer
            Text(highlightFragment(result.fragment, query, result.score != null, highlight), style = MaterialTheme.typography.bodyLarge)
            if (result.entry.tags.isNotEmpty()) Text(
                highlightFragment(result.entry.tags.joinToString("  ") { "#$it" }, query, false, highlight),
                style = MaterialTheme.typography.labelMedium)
        }
    }
}

private fun highlightFragment(fragment: String, query: String, semantic: Boolean, color: Color) = buildAnnotatedString {
    val clean = fragment
    append(clean)
    if (semantic) {
        if (clean.isNotEmpty()) addStyle(SpanStyle(background = color), 0, clean.length)
    } else {
        val words = Regex("[\\p{L}\\p{N}]+").findAll(query).map { it.value }.toSet()
        for (word in words) {
            Regex(Regex.escape(word), RegexOption.IGNORE_CASE).findAll(clean).forEach { match ->
                addStyle(SpanStyle(background = color, fontWeight = FontWeight.Medium), match.range.first, match.range.last + 1)
            }
        }
    }
}

@Composable
private fun EditorScreen(entry: Entry, busy: Boolean, onClose: () -> Unit, onSave: (Entry) -> Unit, onDelete: () -> Unit) {
    var text by remember(entry.id) { mutableStateOf(entry.text) }
    var tags by remember(entry.id) { mutableStateOf(entry.tags.joinToString(", ")) }
    var mood by remember(entry.id) { mutableStateOf(entry.mood) }
    var timestamp by remember(entry.id) { mutableLongStateOf(entry.timestamp) }
    var discard by remember { mutableStateOf(false) }
    val dirty = text != entry.text || tags != entry.tags.joinToString(", ") || mood != entry.mood || timestamp != entry.timestamp
    val close = { if (dirty) discard = true else onClose() }
    BackHandler { if (!busy) close() }
    val context = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(title = { Text(stringResource(if (entry.id == 0L) R.string.new_entry else R.string.edit_entry)) },
            navigationIcon = { IconButton(onClick = close, enabled = !busy) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, stringResource(R.string.back)) } },
            actions = {
                if (entry.id != 0L) IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Outlined.DeleteOutline, stringResource(R.string.delete)) }
                TextButton(onClick = { onSave(entry.copy(timestamp = timestamp, text = text, mood = mood,
                    tags = tags.split(',').map { it.trim().removePrefix("#") }.filter { it.isNotEmpty() }.distinct())) }, enabled = text.isNotBlank() && !busy) { Text(stringResource(R.string.save)) }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
                    DatePickerDialog(context, { _, year, month, day ->
                        timestamp = date.withDayOfMonth(1).withYear(year).withMonth(month+1).withDayOfMonth(day).toInstant().toEpochMilli()
                    }, date.year, date.monthValue-1, date.dayOfMonth).show()
                }, enabled = !busy) { Icon(Icons.Outlined.CalendarToday, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)); Text(formatDate(timestamp)) }
                TextButton(onClick = {
                    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault())
                    TimePickerDialog(context, { _, hour, minute -> timestamp = date.withHour(hour).withMinute(minute).toInstant().toEpochMilli() }, date.hour, date.minute, true).show()
                }, enabled = !busy) { Text(formatTime(timestamp)) }
            }
            Text(stringResource(R.string.mood_optional), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (value in 1..5) FilterChip(selected = mood == value, onClick = { mood = if (mood == value) null else value }, enabled = !busy,
                    label = { Text(value.toString()) })
            }
            OutlinedTextField(tags, { tags = it.take(500) }, label = { Text(stringResource(R.string.tags_label)) },
                supportingText = { Text(stringResource(R.string.tags_hint)) }, modifier = Modifier.fillMaxWidth(), singleLine = true, enabled = !busy)
            TextField(text, { text = it }, placeholder = { Text(stringResource(R.string.editor_hint), fontSize = 20.sp) },
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 32.sp),
                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp), enabled = !busy,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent, disabledContainerColor = Color.Transparent))
            Spacer(Modifier.height(32.dp))
        }
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text(stringResource(R.string.discard_title)) },
        text = { Text(stringResource(R.string.discard_explanation)) },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.discard)) } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text(stringResource(R.string.continue_writing)) } })
}

@Composable
private fun SettingsScreen(state: DiaryUiState, actions: DiaryActions) {
    var passwordAction by remember { mutableStateOf<String?>(null) }
    var exportWarning by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(stringResource(R.string.privacy_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.privacy_detail), style = MaterialTheme.typography.bodyMedium)
        HorizontalDivider()
        Text(stringResource(R.string.lock_timeout), style = MaterialTheme.typography.titleMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to R.string.timeout_immediate, 30 to R.string.timeout_30, 60 to R.string.timeout_60, 300 to R.string.timeout_300).forEach { (seconds, label) ->
                FilterChip(selected = state.timeoutSeconds == seconds, onClick = { actions.onTimeoutChanged(seconds) }, label = { Text(stringResource(label)) }, enabled = !state.busy)
            }
        }
        if (state.biometricAvailable) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.enable_biometrics), Modifier.weight(1f))
            Switch(state.biometricsEnabled, actions.onBiometricsChanged, enabled = !state.busy)
        }
        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = state.selectedLanguage == "ru", onClick = { actions.onLanguageChanged("ru") }, label = { Text(stringResource(R.string.language_ru)) }, enabled = !state.busy)
            FilterChip(selected = state.selectedLanguage == "en", onClick = { actions.onLanguageChanged("en") }, label = { Text(stringResource(R.string.language_en)) }, enabled = !state.busy)
        }
        HorizontalDivider()
        Text(stringResource(R.string.files_title), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = actions.onImport, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.import_files)) }
        Text(stringResource(R.string.import_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { exportWarning = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_markdown)) }
        Text(stringResource(R.string.markdown_warning), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { passwordAction = "backup" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.export_backup)) }
        OutlinedButton(onClick = { passwordAction = "restore" }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.restore_backup)) }
        Text(stringResource(R.string.backup_hint), style = MaterialTheme.typography.bodySmall)
        HorizontalDivider()
        Text(stringResource(R.string.semantic_model), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(if (state.semanticAvailable) R.string.semantic_ready else R.string.semantic_missing), style = MaterialTheme.typography.bodyMedium)
        if (state.indexing) Text(stringResource(R.string.indexing))
        Spacer(Modifier.height(12.dp))
    }
    if (exportWarning) AlertDialog(onDismissRequest = { exportWarning = false },
        title = { Text(stringResource(R.string.markdown_export_title)) },
        text = { Text(stringResource(R.string.markdown_export_explanation)) },
        confirmButton = { TextButton(onClick = { exportWarning = false; actions.onExportMarkdown() }) { Text(stringResource(R.string.export_action)) } },
        dismissButton = { TextButton(onClick = { exportWarning = false }) { Text(stringResource(R.string.cancel)) } })
    passwordAction?.let { kind ->
        PasswordDialog(restore = kind == "restore", onDismiss = { passwordAction = null }, onConfirm = { password ->
            passwordAction = null
            if (kind == "restore") actions.onRestore(password) else actions.onBackup(password)
        })
    }
}

@Composable
private fun PasswordDialog(restore: Boolean, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(if (restore) R.string.restore_backup else R.string.export_backup)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(if (restore) R.string.restore_explanation else R.string.password_explanation))
            OutlinedTextField(password, { password = it }, label = { Text(stringResource(R.string.password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
            if (!restore) OutlinedTextField(repeat, { repeat = it }, label = { Text(stringResource(R.string.repeat_password)) }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        } },
        confirmButton = { TextButton(onClick = { onConfirm(password); password = ""; repeat = "" },
            enabled = if (restore) password.isNotEmpty() else password.length >= 10 && password == repeat) { Text(stringResource(if (restore) R.string.restore else R.string.continue_action)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: Int, subtitle: Int) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(24.dp))
        Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(subtitle), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun localDate(timestamp: Long): LocalDate = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
private fun formatDate(timestamp: Long): String = localDate(timestamp).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
private fun formatTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
private fun formatDateTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT))
