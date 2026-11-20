package ru.privatediary.domain.models

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TrashPolicyTest {
    private val deletedAt = Instant.parse("2026-10-21T00:00:00Z").toEpochMilli()
    private val expiration = Instant.parse("2026-11-20T00:00:00Z").toEpochMilli()

    @Test fun retentionIsThirtyCompleteDays() {
        assertEquals(2_592_000_000L, TrashPolicy.RETENTION_MILLIS)
        assertEquals(expiration, TrashPolicy.expiresAt(deletedAt))
        assertEquals(deletedAt, TrashPolicy.cutoff(expiration))
    }

    @Test fun remainingDaysRoundUpSoALastMillisecondIsStillRecoverable() {
        assertEquals(30, TrashPolicy.remainingDays(deletedAt, deletedAt))
        assertEquals(30, TrashPolicy.remainingDays(deletedAt, deletedAt + 1L))
        assertEquals(29, TrashPolicy.remainingDays(deletedAt, deletedAt + 86_400_000L))
        assertEquals(1, TrashPolicy.remainingDays(deletedAt, expiration - 1L))
    }

    @Test fun expirationAndLaterDatesHaveNoRecoverableDaysRemaining() {
        assertEquals(0, TrashPolicy.remainingDays(deletedAt, expiration))
        assertEquals(0, TrashPolicy.remainingDays(deletedAt, expiration + 1L))
        assertEquals(0, TrashPolicy.remainingDays(deletedAt, expiration + 100L * 86_400_000L))
    }

    @Test fun retentionUsesElapsedDaysAcrossDaylightSavingChanges() {
        // Europe changes its offset during this period; the retention remains 720 hours.
        val beforeChange = Instant.parse("2026-10-10T10:00:00Z").toEpochMilli()
        val afterThirtyDays = Instant.parse("2026-11-09T10:00:00Z").toEpochMilli()
        assertEquals(afterThirtyDays, TrashPolicy.expiresAt(beforeChange))
        assertEquals(beforeChange, TrashPolicy.cutoff(afterThirtyDays))
    }

    @Test fun changingTheClockBackDoesNotShowMoreThanThirtyDays() {
        assertEquals(30, TrashPolicy.remainingDays(deletedAt, deletedAt - 86_400_000L))
    }

    @Test fun extremeTimestampsSaturateInsteadOfWrappingAround() {
        assertEquals(Long.MAX_VALUE, TrashPolicy.expiresAt(Long.MAX_VALUE))
        assertEquals(Long.MAX_VALUE, TrashPolicy.expiresAt(Long.MAX_VALUE - TrashPolicy.RETENTION_MILLIS + 1L))
        assertEquals(Long.MIN_VALUE, TrashPolicy.cutoff(Long.MIN_VALUE))
        assertEquals(Long.MIN_VALUE, TrashPolicy.cutoff(Long.MIN_VALUE + TrashPolicy.RETENTION_MILLIS - 1L))
        assertEquals(0, TrashPolicy.remainingDays(Long.MIN_VALUE, Long.MAX_VALUE))
        assertEquals(30, TrashPolicy.remainingDays(Long.MAX_VALUE, Long.MIN_VALUE))
    }
}
