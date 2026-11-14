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
}
