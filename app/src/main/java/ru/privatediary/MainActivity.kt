package ru.privatediary

import ru.privatediary.ui.screens.DiaryScreens
import ru.privatediary.ui.DiaryActions

import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.privatediary.ui.*
import ru.privatediary.ui.theme.DiaryTheme

class MainActivity : AppCompatActivity() {
    private val vm: DiaryViewModel by viewModels()
    private val graph get() = (application as DiaryApplication).graph
    private lateinit var biometric: BiometricPrompt
    private var pendingReminder: String? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val kind = pendingReminder
        pendingReminder = null
        when (kind) {
            "daily" -> vm.dailyReminderChanged(granted)
            "backup" -> vm.backupReminderChanged(granted)
        }
        if (!granted && kind != null) vm.notify(R.string.reminder_permission_denied)
    }
    private val importFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.fileResult(it) }
    private val restoreFile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { vm.fileResult(listOfNotNull(it)) }
    private val exportZip = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { vm.fileResult(listOfNotNull(it)) }
    private val exportBackup = registerForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { vm.fileResult(listOfNotNull(it)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        super.onCreate(savedInstanceState)
        pendingReminder = savedInstanceState?.getString("pending_reminder")?.takeIf { it in setOf("daily", "backup") }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ru"))
        vm.updateLanguage(AppCompatDelegate.getApplicationLocales().toLanguageTags().ifBlank { "ru" }.substringBefore(','))
        vm.updateBiometricAvailability(BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS)
        biometric = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) { vm.biometricSuccess() }
            override fun onAuthenticationError(code: Int, text: CharSequence) {
                if (code !in listOf(BiometricPrompt.ERROR_USER_CANCELED, BiometricPrompt.ERROR_CANCELED, BiometricPrompt.ERROR_NEGATIVE_BUTTON)) vm.notify(R.string.biometric_error)
            }
        })
        setContent {
            DiaryTheme {
                val state = vm.state.collectAsStateWithLifecycle().value
                DiaryScreens(state, DiaryActions(
                    onCreatePin = vm::createPin, onUnlock = vm::unlock, onBiometric = ::authenticate,
                    onSaveEntry = vm::save, onDeleteEntry = vm::delete,
                    onQueryChanged = vm::queryChanged, onSearchModeChanged = vm::modeChanged,
                    onSearchFiltersChanged = vm::searchFiltersChanged, onOpenSearchResult = vm::openSearchResult,
                    onTimeoutChanged = vm::timeoutChanged, onBiometricsChanged = vm::biometricsChanged,
                    onLanguageChanged = { tag ->
                        vm.updateLanguage(tag)
                        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
                    },
                    onImport = { vm.beginTransfer(DiaryViewModel.Transfer.IMPORT); importFiles.launch(arrayOf("text/plain", "text/markdown", "text/x-markdown")) },
                    onExportMarkdown = { vm.beginTransfer(DiaryViewModel.Transfer.MARKDOWN); exportZip.launch("private-diary.zip") },
                    onBackup = { password -> vm.beginTransfer(DiaryViewModel.Transfer.BACKUP, password); exportBackup.launch("private-diary.pdiary") },
                    onRestore = { password -> vm.beginTransfer(DiaryViewModel.Transfer.RESTORE, password); restoreFile.launch(arrayOf("application/octet-stream", "*/*")) },
                    onDismissError = vm::dismissError, onLock = vm::lock,
                    onOpenEntry = vm::openEntry, onCloseEditor = vm::closeEditor,
                    onDraftChanged = vm::draftChanged, onDiscardDraft = vm::discardDraft,
                    onRestoreEntry = vm::restoreEntry, onDeleteForever = vm::deleteForever, onDismissUndo = vm::dismissUndo,
                    onToggleFavorite = vm::toggleFavorite,
                    onDailyReminderChanged = { setReminder("daily", it) },
                    onBackupReminderChanged = { setReminder("backup", it) },
                    onReminderTimeChanged = vm::reminderTimeChanged,
                ))
            }
        }
    }

    private fun setReminder(kind: String, enabled: Boolean) {
        if (enabled && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingReminder = kind
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        val allowed = !enabled || graph.reminders.canPostNotifications()
        if (kind == "daily") vm.dailyReminderChanged(enabled && allowed) else vm.backupReminderChanged(enabled && allowed)
        if (!allowed) vm.notify(R.string.reminder_permission_denied)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingReminder?.let { outState.putString("pending_reminder", it) }
        super.onSaveInstanceState(outState)
    }

    private fun authenticate() {
        if (!graph.lock.isConfigured || !graph.settings.biometricsEnabled) return
        biometric.authenticate(BiometricPrompt.PromptInfo.Builder()
            .setTitle(getString(R.string.unlock_biometric))
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .setNegativeButtonText(getString(R.string.enter_pin)).build())
    }
    override fun onStart() { graph.lock.onForeground(); super.onStart(); graph.reminders.reschedule() }
    override fun onStop() { vm.flushDraft(); if (!isChangingConfigurations) graph.lock.onBackground(); super.onStop() }
}
