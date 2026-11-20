package ru.privatediary.util.reminders

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import ru.privatediary.MainActivity
import ru.privatediary.R
import ru.privatediary.security.DiarySettings
import java.time.ZoneId
import java.util.Locale

/** Schedules generic, local notifications; never opens the diary database or the model. */
class ReminderScheduler(
    context: Context,
    private val settings: DiarySettings = DiarySettings(context),
) {
    private val context = context.applicationContext
    private val alarms = this.context.getSystemService(AlarmManager::class.java)
    private val notifications = this.context.getSystemService(NotificationManager::class.java)

    fun canPostNotifications(): Boolean =
        (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
            && notifications.areNotificationsEnabled()

    fun reschedule() {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val allowed = canPostNotifications()
        createChannel()
        if (settings.dailyReminderEnabled && allowed) {
            schedule(ACTION_DAILY, DAILY_ID, ReminderSchedule.nextDaily(now, settings.dailyReminderMinutes, zone))
        } else cancel(ACTION_DAILY, DAILY_ID)

        if (settings.backupReminderEnabled && allowed) {
            if (settings.backupReminderEnabledAt <= 0L) settings.backupReminderEnabledAt = now
            if (settings.lastSuccessfulBackupAt > 0L && settings.lastSuccessfulBackupAt >= settings.lastBackupReminderAt) {
                notifications.cancel(BACKUP_ID)
            }
            schedule(ACTION_BACKUP, BACKUP_ID, ReminderSchedule.nextBackup(now, settings.dailyReminderMinutes, zone,
                settings.backupReminderEnabledAt, settings.lastSuccessfulBackupAt, settings.lastBackupReminderAt))
        } else cancel(ACTION_BACKUP, BACKUP_ID)
    }

    @SuppressLint("MissingPermission") // Both the permission and system notification switch are checked below.
    internal fun deliver(action: String?) {
        val daily = action == ACTION_DAILY
        if (!daily && action != ACTION_BACKUP) return
        val enabled = if (daily) settings.dailyReminderEnabled else settings.backupReminderEnabled
        if (!enabled || !canPostNotifications()) { reschedule(); return }
        val localized = localizedContext()
        createChannel(localized)
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = Notification.Builder(localized, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_diary_notification)
            .setContentTitle(localized.getString(if (daily) R.string.reminder_daily_notification_title else R.string.reminder_backup_notification_title))
            .setContentText(localized.getString(if (daily) R.string.reminder_daily_notification_text else R.string.reminder_backup_notification_text))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setAutoCancel(true)
            .build()
        try {
            notifications.notify(if (daily) DAILY_ID else BACKUP_ID, notification)
            if (!daily) settings.lastBackupReminderAt = System.currentTimeMillis()
        } catch (_: SecurityException) {
            // Permission can be revoked between the check and notify; keep the app usable.
        }
        reschedule()
    }

    private fun schedule(action: String, requestCode: Int, at: Long) {
        // Inexact alarms need no exact-alarm permission and may be deferred by Android.
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(action, requestCode))
    }

    private fun cancel(action: String, requestCode: Int) {
        alarms.cancel(alarmIntent(action, requestCode))
        notifications.cancel(requestCode)
    }

    private fun alarmIntent(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(context,
        requestCode, Intent(context, ReminderReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun localizedContext(): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(Locale.forLanguageTag(settings.languageTag))
        return context.createConfigurationContext(configuration)
    }

    private fun createChannel(localized: Context = localizedContext()) {
        notifications.createNotificationChannel(NotificationChannel(CHANNEL_ID,
            localized.getString(R.string.reminder_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = localized.getString(R.string.reminder_channel_description)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        })
    }

    internal companion object {
        const val ACTION_DAILY = "ru.privatediary.action.DAILY_REMINDER"
        const val ACTION_BACKUP = "ru.privatediary.action.BACKUP_REMINDER"
        private const val CHANNEL_ID = "private_diary_reminders"
        private const val DAILY_ID = 4101
        private const val BACKUP_ID = 4102
    }
}
