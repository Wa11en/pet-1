package ru.privatediary.util.reminders

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Explicit, private alarm receiver; notification content never includes diary data. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderScheduler(context).deliver(intent.action)
    }
}
