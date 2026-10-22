package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.loggingClassName
import kotlin.coroutines.cancellation.CancellationException

/** Why a native facade call changed nothing. Coroutine cancellation is not a kind; it stays cancellation. */
enum class NativeFailureKind {
    /** The user or source stopped the operation. Nothing to report. */
    Cancelled,

    /** Shared rules rejected the request, for example a locked Screen or a rejected key. Retrying won't help. */
    Validation,

    /** The Screen, Page, or other target no longer exists. Reload instead of retrying. */
    NotFound,

    /** Storage on this device could not be read or written; retrying may help. */
    Persistence,

    /** A remote service could not be reached or failed; retrying may help. */
    Network,
}

/**
 * The only error the Boards, Settings, and Speech facades throw, besides
 * coroutine cancellation. Swift finds it as `NSError.userInfo["KotlinException"]`
 * and can switch on [kind]. Its message is the kind alone and it keeps no cause,
 * so phrases, keys, and paths never reach native error text.
 */
class NativeFailure(val kind: NativeFailureKind) : Exception(kind.name) {
    val isRetryable: Boolean
        get() = kind == NativeFailureKind.Persistence || kind == NativeFailureKind.Network
}

/**
 * Runs a facade body so native callers see only [NativeFailure] or cancellation.
 * A [NativeFailure] raised by the body passes through; any other exception means
 * [unexpected], which is where that facade's remaining work happens.
 */
internal suspend inline fun <T> nativeCall(
    unexpected: NativeFailureKind = NativeFailureKind.Persistence,
    block: () -> T,
): T = try {
    block()
} catch (failure: CancellationException) {
    throw failure
} catch (failure: NativeFailure) {
    throw failure
} catch (failure: Exception) {
    OperationalLogger.warn("native_facade.call", "failed", exceptionClass = failure.loggingClassName())
    throw NativeFailure(unexpected)
}
