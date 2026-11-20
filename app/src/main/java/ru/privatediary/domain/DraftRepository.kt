package ru.privatediary.domain

import kotlinx.coroutines.flow.Flow
import ru.privatediary.domain.models.EditorDraft

interface DraftRepository {
    fun observeDrafts(): Flow<List<EditorDraft>>
    suspend fun saveDraft(draft: EditorDraft)
    suspend fun deleteDraft(entryId: Long)
}
