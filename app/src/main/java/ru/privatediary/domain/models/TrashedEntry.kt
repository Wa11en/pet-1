package ru.privatediary.domain.models

import kotlin.math.ceil

data class TrashedEntry(val entry: Entry, val deletedAt: Long)

object TrashPolicy {
    const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    fun cutoff(now: Long): Long = if (now < Long.MIN_VALUE + RETENTION_MILLIS) Long.MIN_VALUE else now - RETENTION_MILLIS
    fun expiresAt(deletedAt: Long): Long = if (deletedAt > Long.MAX_VALUE - RETENTION_MILLIS) Long.MAX_VALUE else deletedAt + RETENTION_MILLIS
    fun remainingDays(deletedAt: Long, now: Long): Int =
        ceil((expiresAt(deletedAt).toDouble() - now.toDouble()) / 86_400_000.0).toInt().coerceIn(0, 30)
}
