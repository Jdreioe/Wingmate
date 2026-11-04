package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.CompletableDeferred
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
        var unsaved: UnsavedSpeed? = null
        val saver = SpeedSaver(backgroundScope, save = { saved += it.rate; true }, onUnsavedChange = { unsaved = it })
        saver.loaded(voice)

        saver.changed(voice.copy(rate = 1.1))
        assertTrue(saver.flush())
        advanceUntilIdle()

        assertEquals(listOf(1.1), saved)
        assertEquals(null, unsaved)
    }

    @Test
    fun `a newer speed during a running save is saved after it`() = runTest {
        val saved = mutableListOf<Double?>()
        val firstSave = CompletableDeferred<Boolean>()
        val saver = SpeedSaver(backgroundScope, save = {
            saved += it.rate
            if (saved.size == 1) firstSave.await() else true
        }, onUnsavedChange = {}, settleMillis = 0)
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
        var unsaved: UnsavedSpeed? = null
        val saver = SpeedSaver(backgroundScope, save = { false }, onUnsavedChange = { unsaved = it })
        saver.loaded(voice)

        saver.changed(voice.copy(rate = 0.9))

        assertFalse(saver.flush())
        assertTrue(saver.failed)
        assertEquals(UnsavedSpeed("v", 0.9), unsaved)
    }
}
