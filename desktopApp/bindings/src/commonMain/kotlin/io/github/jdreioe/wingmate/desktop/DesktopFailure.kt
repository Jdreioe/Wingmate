package io.github.jdreioe.wingmate.desktop

import io.github.jdreioe.wingmate.domain.PersistenceException
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.requireValid
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal enum class DesktopFailureKind {
    @SerialName("cancelled") Cancelled,
    @SerialName("validation") Validation,
    @SerialName("notFound") NotFound,
    @SerialName("persistence") Persistence,
    @SerialName("unexpected") Unexpected,
}

/**
 * Thrown where desktop core rejects an operation. [message] is shown to the
 * user, so it must be a fixed sentence that never contains phrases, paths, or
 * other user content.
 */
internal class DesktopFailure(val kind: DesktopFailureKind, override val message: String) : Exception(message)

internal fun invalid(message: String): Nothing = throw DesktopFailure(DesktopFailureKind.Validation, message)
internal fun notFound(message: String): Nothing = throw DesktopFailure(DesktopFailureKind.NotFound, message)
internal inline fun valid(condition: Boolean, message: () -> String) {
    if (!condition) invalid(message())
}

/** The shared graph rules phrase their own messages; desktop reports one fixed sentence instead. */
internal fun BoardSetGraph.requireValidScreen() {
    try {
        requireValid()
    } catch (_: IllegalArgumentException) {
        invalid("This Screen is not valid")
    }
}

@Serializable
internal data class DesktopError(val kind: DesktopFailureKind, val message: String, val retryable: Boolean)

private val replyJson = Json { encodeDefaults = true }

/**
 * Wraps one exported operation as `{"ok": value}` or `{"error": DesktopError}`.
 * This is the only place exceptions become replies: nothing may unwind across
 * the C ABI, and unknown exception messages are never forwarded.
 */
internal fun desktopReply(operation: () -> String): String {
    val error = try {
        return "{\"ok\":${operation()}}"
    } catch (failure: DesktopFailure) {
        DesktopError(failure.kind, failure.message, retryable = false)
    } catch (_: CancellationException) {
        DesktopError(DesktopFailureKind.Cancelled, "Cancelled", retryable = false)
    } catch (_: PersistenceException) {
        persistenceError
    } catch (_: okio.IOException) {
        persistenceError
    } catch (_: Throwable) {
        DesktopError(DesktopFailureKind.Unexpected, "Something went wrong", retryable = false)
    }
    return replyJson.encodeToString(mapOf("error" to error))
}

private val persistenceError =
    DesktopError(DesktopFailureKind.Persistence, "Wingmate could not read or save its data", retryable = true)
