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
import ru.privatediary.DiaryApplication
import ru.privatediary.DiarySession
import ru.privatediary.R
import ru.privatediary.domain.models.Entry
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
    enum class Transfer { IMPORT, MARKDOWN, BACKUP, RESTORE }
    private data class Pending(val type: Transfer, val password: CharArray, val uris: List<Uri>? = null)
    private var pending: Pending? = null

    init {
        // Hide private content immediately: native ONNX cancellation may take time to return.
        viewModelScope.launch {
            graph.lock.unlocked.collect { unlocked ->
                if (!unlocked) {
                    searchJob?.cancel()
                    _state.update { it.copy(unlocked = false, loading = false, entries = emptyList(),
                        results = emptyList(), query = "", errorMessage = null) }
                }
            }
        }
        viewModelScope.launch {
            graph.lock.unlocked.collectLatest { unlocked ->
                searchJob?.cancel()
                _state.update { it.copy(onboarded = graph.lock.isConfigured, unlocked = unlocked, loading = unlocked,
                    entries = emptyList(), results = emptyList(), query = "", errorMessage = null) }
                if (unlocked) {
                    try {
                        val opened = graph.session()
                        session = opened
                        _state.update { it.copy(loading = false) }
                        resumeTransfer()
                        coroutineScope {
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
    fun save(entry: Entry) = operation {
        requireNotNull(session).repository.upsert(entry)
        _state.update { it.copy(savedRevision = it.savedRevision + 1) }
    }
    fun delete(id: Long) = operation { requireNotNull(session).repository.delete(id) }
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
        searchJob?.cancel()
        val snapshot = _state.value
        if (!snapshot.unlocked || snapshot.query.isBlank()) { _state.update { it.copy(results = emptyList()) }; return }
        val active = session ?: return
        searchJob = viewModelScope.launch {
            delay(250)
            try {
                val hits = withContext(Dispatchers.Default) { active.search(snapshot.query, snapshot.searchMode == SearchMode.MEANING) }
                if (graph.lock.unlocked.value) _state.update { it.copy(results = hits) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _state.update { it.copy(results = emptyList(), errorMessage = message(R.string.search_error)) } }
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
                        repository.replaceAll(restored)
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
