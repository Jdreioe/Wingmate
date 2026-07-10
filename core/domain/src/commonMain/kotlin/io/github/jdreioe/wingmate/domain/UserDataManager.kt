package io.github.jdreioe.wingmate.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.builtins.ListSerializer

/**
 * Manages user data export and import operations.
 */
class UserDataManager(private val saidTextRepository: SaidTextRepository) {
    private val json = Json { 
        prettyPrint = true 
        ignoreUnknownKeys = true
    }

    /**
     * Exports all user history as a JSON string.
     */
    suspend fun exportData(): String {
        val history = saidTextRepository.list()
        return json.encodeToString(ListSerializer(SaidText.serializer()), history)
    }

    /**
     * Imports user history from a JSON string.
     * Replaces existing history.
     */
    suspend fun importData(jsonData: String) {
        try {
            val history = json.decodeFromString(ListSerializer(SaidText.serializer()), jsonData)
            if (history.isNotEmpty()) {
                saidTextRepository.deleteAll()
                saidTextRepository.addAll(history)
            }
        } catch (e: Exception) {
            println("Error importing data: ${e.message}")
            throw e
        }
    }
}
