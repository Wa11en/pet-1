package ru.privatediary

import android.app.Application
import android.content.Context
import ru.privatediary.monetization.ads.AdsRuntime
import ru.privatediary.util.reminders.ReminderScheduler

class DiaryApplication : Application() {
    val graph by lazy { AppGraph(this) }
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        AdsRuntime.enterProcess(this)
    }
    override fun onCreate() {
        super.onCreate()
        if (AdsRuntime.onApplicationCreate(this)) return
        ReminderScheduler(this).reschedule()
    }
}
