package io.github.jdreioe.wingmate.infrastructure

import android.content.Context
import io.github.jdreioe.wingmate.domain.CommunicationSessionDataSource
import io.github.jdreioe.wingmate.domain.CommunicationSessionSnapshot
import io.github.jdreioe.wingmate.domain.CommunicationStorageError
import io.github.jdreioe.wingmate.domain.CommunicationStorageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class AndroidPreferencesCommunicationSessionDataSource(
    context: Context,
) : CommunicationSessionDataSource {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val dispatcher = Dispatchers.IO.limitedParallelism(1)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun load(): CommunicationStorageResult<CommunicationSessionSnapshot> =
        withContext(dispatcher) {
            val encoded = preferences.getString(SESSION_KEY, null)
                ?: return@withContext CommunicationStorageResult.Success(CommunicationSessionSnapshot())
            try {
                CommunicationStorageResult.Success(json.decodeFromString(encoded))
            } catch (_: SerializationException) {
                CommunicationStorageResult.Failure(CommunicationStorageError.InvalidData)
            } catch (_: RuntimeException) {
                CommunicationStorageResult.Failure(CommunicationStorageError.Unavailable)
            }
        }

    override suspend fun save(
        snapshot: CommunicationSessionSnapshot,
    ): CommunicationStorageResult<Unit> = withContext(dispatcher) {
        try {
            val saved = preferences.edit()
                .putString(SESSION_KEY, json.encodeToString(CommunicationSessionSnapshot.serializer(), snapshot))
                .commit()
            if (saved) {
                CommunicationStorageResult.Success(Unit)
            } else {
                CommunicationStorageResult.Failure(CommunicationStorageError.WriteFailed)
            }
        } catch (_: RuntimeException) {
            CommunicationStorageResult.Failure(CommunicationStorageError.WriteFailed)
        }
    }

    private companion object {
        const val PREFERENCES_NAME = "communication-session"
        const val SESSION_KEY = "snapshot"
    }
}
