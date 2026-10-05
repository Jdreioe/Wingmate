package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.CommunicationSessionDataSource
import io.github.jdreioe.wingmate.domain.CommunicationSessionSnapshot
import io.github.jdreioe.wingmate.domain.CommunicationStorageError
import io.github.jdreioe.wingmate.domain.CommunicationStorageResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import platform.Foundation.NSUserDefaults

/** Keeps the active and held Message in user defaults so they survive app closure and restart. */
class IosCommunicationSessionDataSource : CommunicationSessionDataSource {
    private val defaults = NSUserDefaults.standardUserDefaults()
    private val mutex = Mutex()
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    override suspend fun load(): CommunicationStorageResult<CommunicationSessionSnapshot> =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val encoded = defaults.stringForKey(SESSION_KEY)
                    ?: return@withLock CommunicationStorageResult.Success(CommunicationSessionSnapshot())
                try {
                    CommunicationStorageResult.Success(
                        json.decodeFromString(CommunicationSessionSnapshot.serializer(), encoded)
                    )
                } catch (_: IllegalArgumentException) {
                    // Includes SerializationException.
                    CommunicationStorageResult.Failure(CommunicationStorageError.InvalidData)
                }
            }
        }

    override suspend fun save(
        snapshot: CommunicationSessionSnapshot,
    ): CommunicationStorageResult<Unit> = withContext(Dispatchers.Default) {
        mutex.withLock {
            val encoded = json.encodeToString(CommunicationSessionSnapshot.serializer(), snapshot)
            defaults.setObject(encoded, forKey = SESSION_KEY)
            if (defaults.synchronize()) {
                CommunicationStorageResult.Success(Unit)
            } else {
                CommunicationStorageResult.Failure(CommunicationStorageError.WriteFailed)
            }
        }
    }

    private companion object {
        const val SESSION_KEY = "communication_session_v1"
    }
}
