package io.github.jdreioe.wingmate.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Saves the speed set on Hear your voice once it settles, not per notch: each save is a full
 * voice selection (analytics and, for cloud voices, re-caching Screens audio).
 *
 * One save runs at a time, owned by [scope] (the app's scope on the main thread, so leaving
 * the page cannot cut it off). Callers join a running save and then save the newest speed if
 * that one was older, so a change followed by Continue saves exactly once. [onUnsavedChange]
 * tells setup which speed is not yet confirmed saved, so it survives the page going away.
 */
internal class SpeedSaver(
    private val scope: CoroutineScope,
    /** Saves the voice with its speed; false if it could not be saved. */
    private val save: suspend (Voice) -> Boolean,
    private val onUnsavedChange: (UnsavedSpeed?) -> Unit,
    private val settleMillis: Long = 800,
) {
    /** The newest voice-with-speed, and the last one confirmed saved. */
    private var latest: Voice? = null
    private var confirmed: Voice? = null
    private var running: Pair<Voice, Deferred<Boolean>>? = null
    private var settling: Job? = null

    /** True when the last save failed; shown on the page until a save succeeds. */
    var failed by mutableStateOf(false)
        private set
    /** True while a caller waits on a save before leaving, so the page can show progress. */
    var flushing by mutableStateOf(false)
        private set

    /** The voice as loaded: already saved. */
    fun loaded(voice: Voice) {
        latest = voice
        confirmed = voice
    }

    /** The speed changed; saves it once it settles. */
    fun changed(voice: Voice) {
        latest = voice
        onUnsavedChange(UnsavedSpeed(voice.name.orEmpty(), voice.rate ?: 1.0))
        settling?.cancel()
        settling = scope.launch {
            delay(settleMillis)
            saveLatest()
        }
    }

    /** A speed setup still held as unsaved when the page opened: show it and save it now. */
    fun restored(voice: Voice) {
        latest = voice
        scope.launch { saveLatest() }
    }

    /** Saves the newest speed before the page is left; false (error shown) if it failed. */
    suspend fun flush(): Boolean {
        settling?.cancel()
        flushing = true
        return try { saveLatest() } finally { flushing = false }
    }

    /** The page is going away another way (Leave setup, recreation): finish any unsaved speed. */
    fun leaving() {
        settling?.cancel()
        if (latest != confirmed) scope.launch { saveLatest() }
    }

    private suspend fun saveLatest(): Boolean {
        while (true) {
            val target = latest ?: return true
            if (target == confirmed) return true
            val (voice, job) = running?.takeIf { it.second.isActive }
                ?: (target to scope.async { save(target) }).also { running = it }
            val saved = job.await()
            if (running?.second === job) running = null
            if (!saved) {
                failed = true
                return false
            }
            confirmed = voice
            if (voice == latest) {
                failed = false
                onUnsavedChange(null)
                return true
            }
            // A newer speed arrived while that one saved: save it too.
        }
    }
}
