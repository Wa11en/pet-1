package ru.privatediary.domain

import kotlinx.coroutines.flow.Flow
import ru.privatediary.domain.models.TrashedEntry

interface TrashRepository {
    fun observeTrash(): Flow<List<TrashedEntry>>
    suspend fun restore(id: Long, now: Long = System.currentTimeMillis()): Boolean
    suspend fun deleteForever(id: Long)
    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int
}
