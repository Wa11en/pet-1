package ru.privatediary.util.reminders

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Calendar arithmetic keeps local reminder times stable across daylight-saving changes. */
object ReminderSchedule {
    fun nextDaily(now: Long, minutes: Int, zone: ZoneId): Long {
        val time = time(minutes)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val todayAt = today.atTime(time).atZone(zone).toInstant().toEpochMilli()
        return if (todayAt > now) todayAt else today.plusDays(1).atTime(time).atZone(zone).toInstant().toEpochMilli()
    }

    /** A successful encrypted backup restarts the seven-calendar-day interval. */
    fun nextBackup(
        now: Long,
        minutes: Int,
        zone: ZoneId,
        enabledAt: Long,
        lastSuccessfulBackupAt: Long,
        lastReminderAt: Long,
    ): Long {
        val time = time(minutes)
        val anchor = maxOf(enabledAt, lastSuccessfulBackupAt, lastReminderAt).takeIf { it > 0L }
            ?.coerceAtMost(now) ?: now
        val dueAt = Instant.ofEpochMilli(anchor).atZone(zone).toLocalDate().plusDays(7)
            .atTime(time).atZone(zone).toInstant().toEpochMilli()
        // After a restart, issue one overdue reminder, rather than replaying missed weeks.
        return maxOf(dueAt, now + 1_000L)
    }

    private fun time(minutes: Int): LocalTime {
        require(minutes in 0..1439) { "Reminder time must be within one day" }
        return LocalTime.of(minutes / 60, minutes % 60)
    }
}
