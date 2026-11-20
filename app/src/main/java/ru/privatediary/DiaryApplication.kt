package ru.privatediary

import android.app.Application
import ru.privatediary.util.reminders.ReminderScheduler

class DiaryApplication : Application() {
    val graph by lazy { AppGraph(this) }
    override fun onCreate() {
        super.onCreate()
        ReminderScheduler(this).reschedule()
    }
}
