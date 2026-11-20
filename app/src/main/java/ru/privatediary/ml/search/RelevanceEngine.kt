package ru.privatediary.ml.search

import ru.privatediary.ml.embedder.EngineAvailability

/** Checks a raw query/fragment pair locally; null means failure, false means a valid rejection. */
interface RelevanceEngine {
    val availability: EngineAvailability
    suspend fun prepare(): EngineAvailability
    suspend fun isRelevant(query: String, fragment: String): Boolean?

    /** Reuse a stored E5 passage vector when supported; implementations must not mutate it. */
    suspend fun isRelevant(query: String, fragment: String, embedding: FloatArray): Boolean? =
        isRelevant(query, fragment)
}
