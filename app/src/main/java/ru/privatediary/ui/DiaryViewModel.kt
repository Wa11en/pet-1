package ru.privatediary.ui

import android.app.Application
import android.content.res.Configuration
import android.net.Uri
import android.provider.OpenableColumns
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import ru.privatediary.DiaryApplication
import ru.privatediary.DiarySession
import ru.privatediary.R
import ru.privatediary.domain.models.Entry
import ru.privatediary.domain.models.EditorDraft
import ru.privatediary.security.lock.PinLock
import ru.privatediary.util.BackupCodec
import ru.privatediary.util.MarkdownExporter
import ru.privatediary.util.TextImporter
import java.io.ByteArrayOutputStream
import java.util.Locale

class DiaryViewModel(application: Application) : AndroidViewModel(application) {
    val graph = (application as DiaryApplication).graph
    private val _state = MutableStateFlow(DiaryUiState(loading = false, onboarded = graph.lock.isConfigured,
        timeoutSeconds = graph.settings.timeoutSeconds, biometricsEnabled = graph.settings.biometricsEnabled))
    val state = _state.asStateFlow()
    private var session: DiarySession? = null
    private val indexingRequests = MutableStateFlow(0L)
    private var searchJob: Job? = null
    private var searchRevision = 0L
    private val draftMutex = Mutex()
    private var draftJob: Job? = null
    private data class PendingDraft(val session: DiarySession, val draft: EditorDraft)
    @Volatile private var pendingDraft: PendingDraft? = null
    enum class Transfer { IMPORT, MARKDOWN, BACKUP, RESTORE }
    private data class Pending(val type: Transfer, val password: CharArray, val uris: List<Uri>? = null)
    private var pending: Pending? = null

    init {
        // Hide private content immediately: native ONNX cancellation may take time to return.
        viewModelScope.launch {
            graph.lock.unlocked.collect { unlocked ->
                if (!unlocked) {
                    flushDraft()
                    searchJob?.cancel()
                    _state.update { it.copy(unlocked = false, loading = false, entries = emptyList(),
                        results = emptyList(), searching = false, query = "", errorMessage = null,
                        drafts = emptyList(), editorEntry = null, editorDraft = null, draftSaving = false) }
                }
            }
        }
        viewModelScope.launch {
            graph.lock.unlocked.collectLatest { unlocked ->
                searchJob?.cancel()
                _state.update { it.copy(onboarded = graph.lock.isConfigured, unlocked = unlocked, loading = unlocked,
                    entries = emptyList(), results = emptyList(), searching = false, query = "", errorMessage = null) }
                if (unlocked) {
                    try {
                        val opened = graph.session()
                        session = opened
                        val drafts = opened.repository.observeDrafts().first()
                        _state.update { it.copy(loading = false, drafts = drafts) }
                        resumeTransfer()
                        coroutineScope {
                            launch { opened.repository.observeDrafts().collect { drafts ->
                                _state.update { it.copy(drafts = drafts) }
                            } }
                            launch { combine(opened.repository.observeEntries().distinctUntilChanged(), indexingRequests) { entries, _ -> entries }
                                .collectLatest { entries ->
                                _state.update { it.copy(entries = entries) }
                                refreshSearch()
                                opened.semantic.indexPending()
                            } }
                            launch { opened.semantic.available.collect { ready -> _state.update { it.copy(semanticAvailable = ready) } } }
                            launch { opened.semantic.indexing.collect { indexing -> _state.update { it.copy(indexing = indexing) } } }
                            launch { opened.semantic.revision.collect { refreshSearch() } }
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { _state.update { it.copy(loading = false, errorMessage = message(R.string.storage_error)) } }
                } else { session = null }
            }
        }
        viewModelScope.launch {
            state.map { it.unlocked && !it.busy }.distinctUntilChanged().collect { ready -> if (ready) resumeTransfer() }
        }
        viewModelScope.launch {
            while (isActive) { _state.update { it.copy(lockRemainingSeconds = graph.lock.remainingCooldownSeconds()) }; delay(1000) }
        }
    }

    fun updateBiometricAvailability(available: Boolean) { _state.update { it.copy(biometricAvailable = available) } }
    fun updateLanguage(tag: String) { _state.update { it.copy(selectedLanguage = tag) } }
    fun createPin(pin: String, biometrics: Boolean) = operation(requireSession = false) {
        graph.settings.biometricsEnabled = biometrics && _state.value.biometricAvailable
        graph.lock.setPin(pin)
        _state.update { it.copy(onboarded = true, biometricsEnabled = graph.settings.biometricsEnabled) }
    }
    fun unlock(pin: String) = operation(requireSession = false) {
        if (graph.lock.verifyPin(pin) != PinLock.PinResult.SUCCESS) _state.update {
            it.copy(errorMessage = message(R.string.wrong_pin), lockRemainingSeconds = graph.lock.remainingCooldownSeconds())
        }
    }
    fun biometricSuccess() { if (graph.settings.biometricsEnabled) graph.lock.unlockWithBiometrics() }
    fun openEntry(entry: Entry) {
        if (!_state.value.unlocked || _state.value.busy) return
        val active = session ?: return
        operation {
            val draft = draftMutex.withLock {
                active.repository.observeDrafts().first().firstOrNull { it.entryId == entry.id }
            } ?: EditorDraft.from(entry)
            if (graph.lock.unlocked.value) _state.update {
                it.copy(editorEntry = entry, editorDraft = draft, draftSaving = false)
            }
        }
    }
    fun draftChanged(draft: EditorDraft) {
        val active = session ?: return
        val state = _state.value
        if (!state.unlocked || state.busy || state.editorEntry?.id != draft.entryId) return
        val valid = draft.validated().copy(updatedAt = System.currentTimeMillis())
        val request = PendingDraft(active, valid)
        pendingDraft = request
        _state.update { it.copy(editorDraft = valid, draftSaving = true) }
        draftJob?.cancel()
        draftJob = viewModelScope.launch { delay(400); persistDraft(request) }
    }
    private suspend fun persistDraft(request: PendingDraft) {
        try {
            withContext(Dispatchers.IO) {
                draftMutex.withLock {
                    if (pendingDraft !== request) return@withLock
                    request.session.repository.saveDraft(request.draft)
                    if (pendingDraft === request) {
                        pendingDraft = null
                        if (graph.lock.unlocked.value) _state.update { it.copy(draftSaving = false) }
                    }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (graph.lock.unlocked.value) notify(R.string.draft_save_error) }
    }
    /** Flush on stop even when the lock is about to hide the editor. No plaintext saved state. */
    fun flushDraft() {
        draftJob?.cancel()
        val request = pendingDraft ?: return
        draftJob = viewModelScope.launch { persistDraft(request) }
    }
    fun closeEditor() {
        if (_state.value.busy) return
        draftJob?.cancel()
        val request = pendingDraft
        viewModelScope.launch {
            if (request != null) persistDraft(request)
            if (pendingDraft == null) _state.update { it.copy(editorEntry = null, editorDraft = null) }
        }
    }
    fun discardDraft() {
        val id = _state.value.editorEntry?.id ?: return
        val active = session ?: return
        operation {
            draftJob?.cancel()
            draftMutex.withLock { active.repository.deleteDraft(id); pendingDraft = null }
            _state.update { it.copy(editorEntry = null, editorDraft = null, draftSaving = false) }
        }
    }
    fun save(entry: Entry) {
        val active = session ?: return
        operation {
            draftJob?.cancel()
            draftMutex.withLock { active.repository.saveEntryAndClearDraft(entry); pendingDraft = null }
            _state.update { it.copy(savedRevision = it.savedRevision + 1, editorEntry = null,
                editorDraft = null, draftSaving = false) }
        }
    }
    fun delete(id: Long) {
        val active = session ?: return
        operation {
            draftJob?.cancel()
            draftMutex.withLock { active.repository.delete(id); pendingDraft = null }
            _state.update { it.copy(editorEntry = null, editorDraft = null, draftSaving = false) }
        }
    }
    fun queryChanged(query: String) { _state.update { it.copy(query = query.take(4096)) }; refreshSearch() }
    fun modeChanged(mode: SearchMode) { _state.update { it.copy(searchMode = mode) }; refreshSearch() }
    fun timeoutChanged(seconds: Int) { graph.settings.timeoutSeconds = seconds; _state.update { it.copy(timeoutSeconds = seconds) } }
    fun biometricsChanged(enabled: Boolean) {
        graph.settings.biometricsEnabled = enabled && _state.value.biometricAvailable
        _state.update { it.copy(biometricsEnabled = graph.settings.biometricsEnabled) }
    }
    fun dismissError() { _state.update { it.copy(errorMessage = null) } }
    fun notify(id: Int) { _state.update { it.copy(errorMessage = message(id)) } }
    fun lock() { graph.lock.lock() }

    private fun refreshSearch() {
        val revision = ++searchRevision
        searchJob?.cancel()
        val snapshot = _state.value
        if (!snapshot.unlocked || snapshot.query.isBlank()) {
            _state.update { it.copy(results = emptyList(), searching = false) }; return
        }
        val active = session ?: run { _state.update { it.copy(results = emptyList(), searching = false) }; return }
        _state.update { it.copy(results = emptyList(), searching = true) }
        searchJob = viewModelScope.launch {
            try {
                delay(250)
                val hits = withContext(Dispatchers.Default) { active.search(snapshot.query, snapshot.searchMode == SearchMode.MEANING) }
                if (revision == searchRevision && graph.lock.unlocked.value) _state.update { it.copy(results = hits) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (revision == searchRevision) _state.update { it.copy(results = emptyList(), errorMessage = message(R.string.search_error)) }
            } finally {
                if (revision == searchRevision) _state.update { it.copy(searching = false) }
            }
        }
    }

    fun beginTransfer(type: Transfer, password: String = "") {
        pending?.password?.fill('\u0000')
        pending = Pending(type, password.toCharArray())
    }
    fun fileResult(uris: List<Uri>) {
        if (uris.isEmpty()) { pending?.password?.fill('\u0000'); pending = null; return }
        pending = pending?.copy(uris = uris)
        resumeTransfer()
    }
    private fun resumeTransfer() {
        if (!graph.lock.unlocked.value || _state.value.busy) return
        val active = session ?: return
        val task = pending?.takeIf { it.uris != null } ?: return
        pending = null
        operation(requireSession = false, errorResource = if (task.type == Transfer.RESTORE) R.string.restore_error else R.string.file_error) {
            val repository = active.repository
            val resolver = getApplication<Application>().contentResolver
            val uris = requireNotNull(task.uris)
            try {
                when (task.type) {
                    Transfer.IMPORT -> {
                        // Parse every file before the first DB write; use atomic insert for the batch.
                        require(uris.size <= 1000)
                        var totalBytes = 0L
                        val imported = uris.map { uri ->
                            val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                                if (cursor.moveToFirst()) cursor.getString(0) else null
                            } ?: "entry.txt"
                            val bytes = resolver.openInputStream(uri)?.use { readLimited(it, 8 * 1024 * 1024) } ?: error("Cannot read")
                            totalBytes += bytes.size
                            require(totalBytes <= 100L * 1024 * 1024)
                            TextImporter.import(name, bytes).also { require(it.text.length <= 1_000_000) }
                        }
                        repository.importEntries(imported)
                    }
                    Transfer.MARKDOWN -> resolver.openOutputStream(uris.first(), "wt")?.use { output ->
                        MarkdownExporter.write(repository.allEntries(), output)
                    } ?: error("Cannot write")
                    Transfer.BACKUP -> {
                        val encrypted = BackupCodec.encode(repository.allEntries(), task.password)
                        resolver.openOutputStream(uris.first(), "wt")?.use { it.write(encrypted) } ?: error("Cannot write")
                    }
                    Transfer.RESTORE -> {
                        val encrypted = resolver.openInputStream(uris.first())?.use { readLimited(it, BackupCodec.MAX_FILE_BYTES) } ?: error("Cannot read")
                        val restored = BackupCodec.decode(encrypted, task.password)
                        draftJob?.cancel()
                        draftMutex.withLock { repository.replaceAll(restored); pendingDraft = null }
                        _state.update { it.copy(editorEntry = null, editorDraft = null, drafts = emptyList()) }
                        // Even an identical snapshot has cleared embeddings and needs a new index.
                        indexingRequests.update { it + 1 }
                    }
                }
                notify(R.string.file_success)
            } finally { task.password.fill('\u0000') }
        }
    }

    private fun operation(requireSession: Boolean = true, errorResource: Int = R.string.operation_error, block: suspend () -> Unit) {
        if (_state.value.busy || (requireSession && !graph.lock.unlocked.value)) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try { withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { notify(errorResource) }
            finally { _state.update { it.copy(busy = false) } }
        }
    }
    private fun message(id: Int): String {
        val context = getApplication<Application>()
        val tag = AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { "ru" }
        val config = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return context.createConfigurationContext(config).getString(id)
    }
    override fun onCleared() { pending?.password?.fill('\u0000'); super.onCleared() }
}

private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        require(output.size().toLong() + count <= limit) { "File too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
