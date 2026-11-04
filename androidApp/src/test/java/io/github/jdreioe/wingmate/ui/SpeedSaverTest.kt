package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedSaverTest {
    private val voice = Voice(name = "v", rate = 1.0)

    @Test
    fun `a change followed by Continue saves exactly once`() = runTest {
        val saved = mutableListOf<Double?>()
        var draft: UnsavedSpeed? = null
        val saver = draftSaver(backgroundScope, save = { saved += it.rate; true }, draft = { draft }, setDraft = { draft = it })
        saver.loaded(voice)

        saver.changed(voice.copy(rate = 1.1))
        assertTrue(saver.flush())
        advanceUntilIdle()

        assertEquals(listOf(1.1), saved)
        assertEquals(null, draft)
    }

    @Test
    fun `a newer speed during a running save is saved after it`() = runTest {
        val saved = mutableListOf<Double?>()
        val firstSave = CompletableDeferred<Boolean>()
        val saver = SpeedSaver(backgroundScope, save = {
            saved += it.rate
            if (saved.size == 1) firstSave.await() else true
        }, onUnsaved = {}, onSaved = {}, settleMillis = 0)
        saver.loaded(voice)

        saver.changed(voice.copy(rate = 1.1))
        runCurrent()
        saver.changed(voice.copy(rate = 1.2))
        val flush = async { saver.flush() }
        firstSave.complete(true)

        assertTrue(flush.await())
        assertEquals(listOf(1.1, 1.2), saved)
    }

    @Test
    fun `a failed save keeps the speed unsaved and reports it`() = runTest {
        var draft: UnsavedSpeed? = null
        val saver = draftSaver(backgroundScope, save = { false }, draft = { draft }, setDraft = { draft = it })
        saver.loaded(voice)

        saver.changed(voice.copy(rate = 0.9))

        assertFalse(saver.flush())
        assertTrue(saver.failed)
        assertEquals(UnsavedSpeed("v", 0.9), draft)
    }

    @Test
    fun `returning to the saved speed waits for a running save and saves the speed back`() = runTest {
        var stored = 1.0
        val firstSave = CompletableDeferred<Boolean>()
        val saver = SpeedSaver(backgroundScope, save = {
            if (it.rate == 1.1) firstSave.await()
            stored = it.rate!!
            true
        }, onUnsaved = {}, onSaved = {}, settleMillis = 0)
        saver.loaded(voice)

        saver.changed(voice.copy(rate = 1.1))
        runCurrent()
        saver.changed(voice.copy(rate = 1.0))
        val flush = async { saver.flush() }
        runCurrent()
        firstSave.complete(true)

        assertTrue(flush.await())
        assertEquals(1.0, stored, 0.0)
    }

    @Test
    fun `a saver from before recreation does not clear a newer unsaved speed`() = runTest {
        var draft: UnsavedSpeed? = null
        val oldSave = CompletableDeferred<Boolean>()
        val old = draftSaver(backgroundScope, save = { oldSave.await() }, draft = { draft }, setDraft = { draft = it })
        old.loaded(voice)
        old.changed(voice.copy(rate = 1.1))
        old.leaving()
        runCurrent()

        val recreated = draftSaver(backgroundScope, save = { false }, draft = { draft }, setDraft = { draft = it })
        recreated.loaded(voice)
        recreated.changed(voice.copy(rate = 1.2))
        assertFalse(recreated.flush())
        oldSave.complete(true)
        advanceUntilIdle()

        assertEquals(UnsavedSpeed("v", 1.2), draft)
    }

    /** Wired the way setup holds its draft: a save clears it only when it names that exact speed. */
    private fun draftSaver(
        scope: CoroutineScope,
        save: suspend (Voice) -> Boolean,
        draft: () -> UnsavedSpeed?,
        setDraft: (UnsavedSpeed?) -> Unit,
    ) = SpeedSaver(
        scope,
        save = save,
        onUnsaved = setDraft,
        onSaved = { if (draft() == it) setDraft(null) },
        settleMillis = 0,
    )
}
