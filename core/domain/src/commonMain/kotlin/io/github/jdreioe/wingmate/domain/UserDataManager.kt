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
     * Replaces existing history. The repository has no transaction primitive,
     * so the current history is snapshotted first; if the import fails partway,
     * the snapshot is restored instead of leaving history destroyed.
     */
    suspend fun importData(jsonData: String) {
        try {
            val history = json.decodeFromString(ListSerializer(SaidText.serializer()), jsonData)
            if (history.isNotEmpty()) {
                val backup = saidTextRepository.list()
                try {
                    saidTextRepository.deleteAll()
                    saidTextRepository.addAll(history)
                } catch (e: Exception) {
                    runCatching {
                        saidTextRepository.deleteAll()
                        saidTextRepository.addAll(backup)
                    }
                    throw e
                }
            }
        } catch (e: Exception) {
            OperationalLogger.warn(
                operation = "user_data.import",
                outcome = "failed",
                exceptionClass = e.loggingClassName(),
            )
            throw e
        }
    }
}
