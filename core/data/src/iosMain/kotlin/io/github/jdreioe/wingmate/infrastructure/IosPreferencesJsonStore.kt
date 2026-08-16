package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PersistenceError
import io.github.jdreioe.wingmate.domain.PersistenceException
import io.github.jdreioe.wingmate.domain.PersistenceRead
import io.github.jdreioe.wingmate.domain.valueOr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import platform.Foundation.NSUserDefaults

/** Serializes read-modify-write operations and never treats malformed preferences as absent. */
internal class IosPreferencesJsonStore<T>(
    private val key: String,
    private val encode: (T) -> String,
    private val decode: (String) -> T,
    private val defaults: NSUserDefaults = NSUserDefaults.standardUserDefaults(),
    private val synchronize: () -> Boolean = { defaults.synchronize() },
) {
    private val mutex = Mutex()

    suspend fun read(defaultValue: () -> T): T = withContext(Dispatchers.Default) {
        mutex.withLock { readLocked().valueOr(defaultValue) }
    }

    suspend fun readNullable(): T? = withContext(Dispatchers.Default) {
        mutex.withLock {
            when (val result = readLocked()) {
                PersistenceRead.Absent -> null
                is PersistenceRead.Loaded -> result.value
                is PersistenceRead.Failed -> throw PersistenceException(result.error, result.cause)
            }
        }
    }

    suspend fun update(defaultValue: () -> T, transform: (T) -> T): T =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val updated = transform(readLocked().valueOr(defaultValue))
                writeLocked(updated)
                updated
            }
        }

    suspend fun replace(value: T, defaultValue: () -> T) = withContext(Dispatchers.Default) {
        mutex.withLock {
            readLocked().valueOr(defaultValue) // Guard against replacing an unreadable payload.
            writeLocked(value)
        }
    }

    private fun readLocked(): PersistenceRead<T> {
        val raw = defaults.stringForKey(key)
        if (raw == null) {
            return if (defaults.objectForKey(key) == null) {
                PersistenceRead.Absent
            } else {
                PersistenceRead.Failed(PersistenceError.CorruptOrUnsupported)
            }
        }
        return try {
            PersistenceRead.Loaded(decode(raw))
        } catch (error: Exception) {
            quarantine(raw)
            PersistenceRead.Failed(PersistenceError.CorruptOrUnsupported, error)
        }
    }

    private fun writeLocked(value: T) {
        val raw = try {
            encode(value)
        } catch (error: Exception) {
            throw PersistenceException(PersistenceError.Io, error)
        }
        val previous = defaults.objectForKey(key)
        defaults.setObject(raw, forKey = key)
        if (!synchronize()) {
            if (previous == null) defaults.removeObjectForKey(key)
            else defaults.setObject(previous, forKey = key)
            defaults.synchronize()
            throw PersistenceException(PersistenceError.Io)
        }
    }

    private fun quarantine(raw: String) {
        val quarantineKey = "${key}_corrupt_backup"
        if (defaults.objectForKey(quarantineKey) != null) return
        defaults.setObject(raw, forKey = quarantineKey)
        defaults.synchronize()
    }
}
