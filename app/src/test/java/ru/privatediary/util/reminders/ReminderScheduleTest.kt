package ru.privatediary.util.reminders

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReminderScheduleTest {
    private val utc = ZoneId.of("UTC")

    @Test fun beforeCutoffUsesTodayAndAtCutoffUsesTomorrow() {
        assertAt("2026-11-20T20:00:00Z", ReminderSchedule.nextDaily(at("2026-11-20T19:59:59Z"), 1200, utc))
        assertAt("2026-11-21T20:00:00Z", ReminderSchedule.nextDaily(at("2026-11-20T20:00:00Z"), 1200, utc))
    }

    @Test fun dailyCrossesYearAndUsesLocalTimezone() {
        assertAt("2027-01-01T00:00:00Z", ReminderSchedule.nextDaily(at("2026-12-31T23:59:59Z"), 0, utc))
        assertAt("2026-11-21T17:00:00Z", ReminderSchedule.nextDaily(at("2026-11-20T17:00:00Z"),
            1200, ZoneId.of("Europe/Moscow")))
    }

    @Test fun springForwardUsesCalendarDayRatherThanTwentyFourHours() {
        val berlin = ZoneId.of("Europe/Berlin")
        assertAt("2026-03-29T06:00:00Z", ReminderSchedule.nextDaily(at("2026-03-28T07:00:00Z"), 480, berlin))
    }

    @Test fun missingSpringTimeMovesForwardByTheGap() {
        assertAt("2026-03-29T01:30:00Z", ReminderSchedule.nextDaily(at("2026-03-29T00:00:00Z"),
            150, ZoneId.of("Europe/Berlin")))
    }

    @Test fun autumnOverlapSchedulesOneDailyReminder() {
        val berlin = ZoneId.of("Europe/Berlin")
        assertAt("2026-10-25T00:30:00Z", ReminderSchedule.nextDaily(at("2026-10-24T23:00:00Z"), 150, berlin))
        // After the earlier 02:30 has passed, do not deliver again at the repeated 02:30.
        assertAt("2026-10-26T01:30:00Z", ReminderSchedule.nextDaily(at("2026-10-25T00:45:00Z"), 150, berlin))
    }

    @Test fun enablingBackupStartsOneWeekFromTodayAtTheSelectedTime() {
        val now = at("2026-11-20T11:00:00Z")
        assertAt("2026-11-27T20:00:00Z", ReminderSchedule.nextBackup(now, 1200, utc, now, 0, 0))
    }

    @Test fun successfulBackupRestartsTheWeek() {
        assertAt("2026-11-29T20:00:00Z", ReminderSchedule.nextBackup(at("2026-11-23T10:00:00Z"),
            1200, utc, at("2026-11-20T11:00:00Z"), at("2026-11-22T10:00:00Z"), 0))
    }

    @Test fun reminderDeliveryStartsTheNextWeek() {
        assertAt("2026-12-04T20:00:00Z", ReminderSchedule.nextBackup(at("2026-11-27T20:30:00Z"),
            1200, utc, at("2026-11-20T11:00:00Z"), 0, at("2026-11-27T20:30:00Z")))
    }

    @Test fun overdueWeeksProduceOneUpcomingReminder() {
        val now = at("2026-11-20T11:00:00Z")
        assertEquals(now + 1000, ReminderSchedule.nextBackup(now, 1200, utc, at("2026-01-01T11:00:00Z"), 0, 0))
    }

    @Test fun weeklyReminderUsesCalendarDaysAcrossDst() {
        assertAt("2026-03-31T18:00:00Z", ReminderSchedule.nextBackup(at("2026-03-24T10:00:00Z"),
            1200, ZoneId.of("Europe/Berlin"), at("2026-03-24T10:00:00Z"), 0, 0))
    }

    @Test fun missingAnchorOrAClockRollbackStillSchedulesInTheFuture() {
        val now = at("2026-11-20T11:00:00Z")
        val expected = at("2026-11-27T20:00:00Z")
        assertEquals(expected, ReminderSchedule.nextBackup(now, 1200, utc, 0, 0, 0))
        assertEquals(expected, ReminderSchedule.nextBackup(now, 1200, utc, at("2027-01-01T11:00:00Z"), 0, 0))
        assertTrue(expected > now)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidMinutesAreRejected() { ReminderSchedule.nextDaily(at("2026-11-20T00:00:00Z"), 1440, utc) }

    private fun at(value: String) = Instant.parse(value).toEpochMilli()
    private fun assertAt(expected: String, actual: Long) = assertEquals(at(expected), actual)
}
