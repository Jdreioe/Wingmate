package io.github.jdreioe.wingmate.application

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel

/** Cancels a native observation. Swift keeps it for as long as it observes. */
class NativeSubscription internal constructor(private val scope: CoroutineScope) {
    fun cancel() { scope.cancel() }
}
