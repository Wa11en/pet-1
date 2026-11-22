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
import ru.privatediary.domain.models.SearchFilters
import ru.privatediary.domain.models.SearchResult
import ru.privatediary.util.SearchFocus
import ru.privatediary.security.lock.PinLock
import ru.privatediary.util.BackupCodec
import ru.privatediary.util.MarkdownExporter
import ru.privatediary.util.TextImporter
import ru.privatediary.monetization.PaymentStatus
import ru.privatediary.monetization.ProScreenState
import java.io.ByteArrayOutputStream
import java.util.Locale

class DiaryViewModel(application: Application) : AndroidViewModel(application) {
    val graph = (application as DiaryApplication).graph
    private val _state = MutableStateFlow(DiaryUiState(loading = false, onboarded = graph.lock.isConfigured,
        timeoutSeconds = graph.settings.timeoutSeconds, biometricsEnabled = graph.settings.biometricsEnabled,
        dailyReminderEnabled = graph.settings.dailyReminderEnabled, dailyReminderMinutes = graph.settings.dailyReminderMinutes,
        backupReminderEnabled = graph.settings.backupReminderEnabled))
    val state = _state.asStateFlow()
    private val _paymentUrls = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val paymentUrls = _paymentUrls.asSharedFlow()
    private var paymentJob: Job? = null
    private var paymentRevision = 0L
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
                    stopPaymentRequests()
                    flushDraft()
                    searchJob?.cancel()
                    _state.update { it.copy(unlocked = false, loading = false, entries = emptyList(),
                        results = emptyList(), searching = false, query = "", errorMessage = null,
                        drafts = emptyList(), editorEntry = null, editorDraft = null, draftSaving = false,
                        trash = emptyList(), undoEntryId = null, searchFilters = SearchFilters(), editorFocusMatch = false,
                        pro = ProScreenState()) }
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
                        opened.repository.purgeExpired()
                        val drafts = opened.repository.observeDrafts().first()
                        _state.update { it.copy(loading = false, drafts = drafts) }
                        resumeTransfer()
                        coroutineScope {
                            launch {
                                while (isActive) {
                                    refreshProAccess()
                                    delay(30_000)
                                }
                            }
                            launch { opened.repository.observeTrash().collect { trash -> _state.update { it.copy(trash = trash) } } }
                            launch { while (isActive) { delay(60 * 60 * 1000L); opened.repository.purgeExpired() } }
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
    fun updateLanguage(tag: String) {
        val language = if (tag.substringBefore('-') == "en") "en" else "ru"
        graph.settings.languageTag = language
        graph.reminders.reschedule()
        _state.update { it.copy(selectedLanguage = language) }
    }
    fun dailyReminderChanged(enabled: Boolean) {
        graph.settings.dailyReminderEnabled = enabled
        graph.reminders.reschedule()
        _state.update { it.copy(dailyReminderEnabled = enabled) }
    }
    fun backupReminderChanged(enabled: Boolean) {
        if (enabled && !graph.settings.backupReminderEnabled) graph.settings.backupReminderEnabledAt = System.currentTimeMillis()
        graph.settings.backupReminderEnabled = enabled
        graph.reminders.reschedule()
        _state.update { it.copy(backupReminderEnabled = enabled) }
    }
    fun reminderTimeChanged(minutes: Int) {
        graph.settings.dailyReminderMinutes = minutes
        graph.reminders.reschedule()
        _state.update { it.copy(dailyReminderMinutes = minutes) }
    }
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
    fun openEntry(entry: Entry) { openEditor(entry) }
    fun openSearchResult(result: SearchResult) {
        openEditor(result.entry, result.fragment.takeIf { _state.value.query.isNotBlank() }, _state.value.query, result.score != null)
    }
    private fun openEditor(entry: Entry, fragment: String? = null, query: String = "", semantic: Boolean = false) {
        if (!_state.value.unlocked || _state.value.busy) return
        val active = session ?: return
        operation {
            val current = if (entry.id == 0L) entry else active.repository.get(entry.id)
                ?: return@operation
            val recovered = draftMutex.withLock {
                active.repository.observeDrafts().first().firstOrNull { it.entryId == current.id }
            } ?: EditorDraft.from(current)
            val selection = fragment?.let { SearchFocus.selection(recovered.text, it, query, semantic) }
            val draft = if (selection == null) recovered else recovered.copy(
                selectionStart = selection.start, selectionEnd = selection.endExclusive)
            if (graph.lock.unlocked.value) _state.update {
                it.copy(editorEntry = current, editorDraft = draft, draftSaving = false, editorFocusMatch = selection != null)
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
            _state.update { it.copy(editorEntry = null, editorDraft = null, draftSaving = false,
                undoEntryId = id, undoRevision = it.undoRevision + 1) }
        }
    }
    fun restoreEntry(id: Long) {
        val active = session ?: return
        operation {
            if (!active.repository.restore(id)) { active.repository.purgeExpired(); notify(R.string.trash_expired) }
            _state.update { it.copy(undoEntryId = null) }
        }
    }
    fun deleteForever(id: Long) {
        val active = session ?: return
        operation { active.repository.deleteForever(id); _state.update { it.copy(undoEntryId = null) } }
    }
    fun dismissUndo() { _state.update { it.copy(undoEntryId = null) } }
    fun toggleFavorite(id: Long) {
        val active = session ?: return
        operation {
            val entry = active.repository.get(id) ?: return@operation
            active.repository.setFavorite(id, !entry.isFavorite)
            if (graph.lock.unlocked.value) _state.update {
                it.copy(editorEntry = it.editorEntry?.let { editing ->
                    if (editing.id == id) editing.copy(isFavorite = !entry.isFavorite) else editing
                })
            }
        }
    }
    fun queryChanged(query: String) { _state.update { it.copy(query = query.take(4096)) }; refreshSearch() }
    fun modeChanged(mode: SearchMode) { _state.update { it.copy(searchMode = mode) }; refreshSearch() }
    fun searchFiltersChanged(filters: SearchFilters) { _state.update { it.copy(searchFilters = filters) }; refreshSearch() }
    fun timeoutChanged(seconds: Int) { graph.settings.timeoutSeconds = seconds; _state.update { it.copy(timeoutSeconds = seconds) } }
    fun biometricsChanged(enabled: Boolean) {
        graph.settings.biometricsEnabled = enabled && _state.value.biometricAvailable
        _state.update { it.copy(biometricsEnabled = graph.settings.biometricsEnabled) }
    }
    fun dismissError() { _state.update { it.copy(errorMessage = null) } }
    fun notify(id: Int) { _state.update { it.copy(errorMessage = message(id)) } }
    fun lock() { graph.lock.lock() }

    /** All startup/unlock/expiry checks are local; payment requests exist only behind these buttons. */
    private suspend fun refreshProAccess() {
        val access = try { graph.proAccess.readAccess() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (graph.lock.unlocked.value) _state.update { it.copy(pro = ProScreenState()) }
            return
        }
        if (!graph.lock.unlocked.value) return
        _state.update { it.copy(pro = it.pro.copy(
            paymentsConfigured = graph.payments.isConfigured && graph.proAccess.isVerifierConfigured,
            activeUntilMillis = access.activeUntilMillis, hasAccount = access.hasAccount,
            hasPendingCheckout = access.pendingCheckout != null,
        )) }
    }

    fun pay() = paymentOperation {
        val pendingCheckout = graph.proAccess.readAccess().pendingCheckout
        val checkout = pendingCheckout ?: graph.payments.createCheckout(graph.proAccess.checkoutIdentity())
            .also { graph.proAccess.saveCheckout(it) }
        refreshProAccess()
        if (graph.lock.unlocked.value) _paymentUrls.emit(checkout.paymentUrl)
    }

    fun checkPayment() = paymentOperation {
        val checkout = graph.proAccess.readAccess().pendingCheckout ?: return@paymentOperation
        val result = graph.payments.checkPayment(graph.proAccess.checkoutIdentity(), checkout.checkoutId)
        val messageId = when (result.status) {
            PaymentStatus.PENDING -> R.string.pro_status_pending
            PaymentStatus.CANCELED -> { graph.proAccess.clearPendingCheckout(); R.string.pro_status_canceled }
            PaymentStatus.PAID -> {
                val raw = result.entitlement
                if (raw == null) {
                    // A completed order may already be expired. Clear only the pending order, never grant access.
                    graph.proAccess.clearPendingCheckout()
                    R.string.pro_status_expired
                } else {
                    check(graph.proAccess.acceptEntitlement(raw)) { "Invalid signed entitlement" }
                    R.string.pro_status_activated
                }
            }
        }
        refreshProAccess()
        if (graph.lock.unlocked.value) _state.update { it.copy(pro = it.pro.copy(message = message(messageId))) }
    }

    fun restorePro(code: String) = paymentOperation {
        val identity = graph.proAccess.restoreIdentity(code)
        val raw = graph.payments.restore(identity)
        if (raw == null) {
            _state.update { it.copy(pro = it.pro.copy(message = message(R.string.pro_restore_not_found))) }
        } else {
            check(graph.proAccess.acceptRestoredEntitlement(identity, raw))
            refreshProAccess()
            if (graph.lock.unlocked.value) _state.update { it.copy(pro = it.pro.copy(message = message(R.string.pro_status_activated))) }
        }
    }

    fun revealRecoveryCode() {
        if (!graph.lock.unlocked.value) return
        viewModelScope.launch {
            val code = runCatching { graph.proAccess.recoveryCode() }.getOrNull()
            if (graph.lock.unlocked.value) _state.update { it.copy(pro = it.pro.copy(recoveryCode = code)) }
        }
    }

    fun closePro() { stopPaymentRequests() }

    fun stopPaymentRequests() {
        paymentRevision++
        paymentJob?.cancel()
        graph.payments.cancelActiveRequests()
        _state.update { it.copy(pro = it.pro.copy(busy = false, recoveryCode = null)) }
    }

    private fun paymentOperation(block: suspend () -> Unit) {
        if (!graph.lock.unlocked.value || _state.value.pro.busy || !_state.value.pro.paymentsConfigured) return
        val revision = ++paymentRevision
        _state.update { it.copy(pro = it.pro.copy(busy = true, message = null)) }
        paymentJob = viewModelScope.launch {
            try { block() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (revision == paymentRevision && graph.lock.unlocked.value) _state.update { it.copy(pro = it.pro.copy(message = message(R.string.pro_payment_error))) }
            } finally {
                if (revision == paymentRevision && graph.lock.unlocked.value) _state.update { it.copy(pro = it.pro.copy(busy = false)) }
            }
        }
    }

    private fun refreshSearch() {
        val revision = ++searchRevision
        searchJob?.cancel()
        val snapshot = _state.value
        if (!snapshot.unlocked || (snapshot.query.isBlank() && snapshot.searchFilters.isEmpty)) {
            _state.update { it.copy(results = emptyList(), searching = false) }; return
        }
        val active = session ?: run { _state.update { it.copy(results = emptyList(), searching = false) }; return }
        _state.update { it.copy(results = emptyList(), searching = true) }
        searchJob = viewModelScope.launch {
            try {
                delay(250)
                val hits = withContext(Dispatchers.Default) { active.search(snapshot.query, snapshot.searchMode == SearchMode.MEANING, snapshot.searchFilters) }
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
                        graph.settings.lastSuccessfulBackupAt = System.currentTimeMillis()
                        graph.reminders.reschedule()
                    }
                    Transfer.RESTORE -> {
                        val encrypted = resolver.openInputStream(uris.first())?.use { readLimited(it, BackupCodec.MAX_FILE_BYTES) } ?: error("Cannot read")
                        val restored = BackupCodec.decode(encrypted, task.password)
                        draftJob?.cancel()
                        draftMutex.withLock { repository.replaceAll(restored); pendingDraft = null }
                        _state.update { it.copy(editorEntry = null, editorDraft = null, drafts = emptyList(), undoEntryId = null) }
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
    override fun onCleared() { graph.payments.cancelActiveRequests(); pending?.password?.fill('\u0000'); super.onCleared() }
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
