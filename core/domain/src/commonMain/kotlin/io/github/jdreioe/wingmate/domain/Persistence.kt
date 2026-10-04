package io.github.jdreioe.wingmate.domain

/** Privacy-safe categories for failures while reading or writing user data. */
enum class PersistenceError {
    CorruptOrUnsupported,
    Io,
    SecureStorage,
}

/**
 * Thrown by repository adapters when persisted user data cannot be trusted.
 * Callers may safely use [error] for UI state; the message never contains payload data.
 */
class PersistenceException(
    val error: PersistenceError,
    cause: Throwable? = null,
) : Exception(
    when (error) {
        PersistenceError.CorruptOrUnsupported -> "Stored data is corrupt or unsupported"
        PersistenceError.Io -> "Stored data could not be read or saved"
        PersistenceError.SecureStorage -> "Secure storage is unavailable"
    },
    cause,
)

/** Distinguishes first run from a successfully loaded value, including an empty value. */
sealed interface PersistenceRead<out T> {
    data object Absent : PersistenceRead<Nothing>
    data class Loaded<T>(val value: T) : PersistenceRead<T>
    data class Failed(val error: PersistenceError, val cause: Throwable? = null) : PersistenceRead<Nothing>
}

fun <T> PersistenceRead<T>.valueOr(defaultValue: () -> T): T = when (this) {
    PersistenceRead.Absent -> defaultValue()
    is PersistenceRead.Loaded -> value
    is PersistenceRead.Failed -> throw PersistenceException(error, cause)
}
