package ru.privatediary.security

import android.content.Context

class DiarySettings(context: Context) {
    private val preferences = context.getSharedPreferences("diary_settings", Context.MODE_PRIVATE)
    var timeoutSeconds: Int
        get() = preferences.getInt("lock_timeout", 30).coerceIn(0, 300)
        set(value) { require(value in setOf(0, 30, 60, 300)); preferences.edit().putInt("lock_timeout", value).apply() }
    var lockTimeoutSeconds: Int
        get() = timeoutSeconds
        set(value) { timeoutSeconds = value }
    var biometricsEnabled: Boolean
        get() = preferences.getBoolean("biometrics", false)
        set(value) { preferences.edit().putBoolean("biometrics", value).apply() }
    var dailyReminderEnabled: Boolean
        get() = preferences.getBoolean("daily_reminder", false)
        set(value) { preferences.edit().putBoolean("daily_reminder", value).apply() }
    var dailyReminderMinutes: Int
        get() = preferences.getInt("reminder_minutes", 20 * 60).coerceIn(0, 1439)
        set(value) { require(value in 0..1439); preferences.edit().putInt("reminder_minutes", value).apply() }
    var backupReminderEnabled: Boolean
        get() = preferences.getBoolean("backup_reminder", false)
        set(value) { preferences.edit().putBoolean("backup_reminder", value).apply() }
    var backupReminderEnabledAt: Long
        get() = preferences.getLong("backup_reminder_enabled_at", 0L)
        set(value) { preferences.edit().putLong("backup_reminder_enabled_at", value).apply() }
    var lastSuccessfulBackupAt: Long
        get() = preferences.getLong("last_backup_at", 0L)
        set(value) { preferences.edit().putLong("last_backup_at", value).apply() }
    var lastBackupReminderAt: Long
        get() = preferences.getLong("backup_reminder_at", 0L)
        set(value) { preferences.edit().putLong("backup_reminder_at", value).apply() }
    var languageTag: String
        get() = preferences.getString("language", "ru").takeIf { it == "en" } ?: "ru"
        set(value) { require(value in setOf("ru", "en")); preferences.edit().putString("language", value).apply() }
}
