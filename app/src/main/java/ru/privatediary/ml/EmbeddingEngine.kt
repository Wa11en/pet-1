package ru.privatediary.ml

enum class EngineAvailability { READY, MODEL_MISSING, UNAVAILABLE }

/** Engines return null when the optional local model cannot be used. No network fallback exists. */
interface EmbeddingEngine {
    val availability: EngineAvailability
    suspend fun prepare(): EngineAvailability
    suspend fun embedPassage(text: String): FloatArray?
    suspend fun embedQuery(text: String): FloatArray?
    suspend fun chunk(text: String): List<TextChunk>
}
