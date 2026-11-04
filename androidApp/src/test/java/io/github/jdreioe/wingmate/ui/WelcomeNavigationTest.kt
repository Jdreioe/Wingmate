package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.ui.WelcomeStep.*
import org.junit.Assert.assertEquals
import org.junit.Test

class WelcomeNavigationTest {
    private val atReview = WelcomeNavigation(listOf(Start, VoiceEngine, Voice, Language, TestVoice, Workspace, Privacy, Review))

    @Test
    fun `a repeated activation moves only once`() {
        val once = WelcomeNavigation(listOf(Start, VoiceEngine, Voice)).advance(Voice, Language)
        assertEquals(once, once.advance(Voice, Language))

        val backOnce = once.back(Language)
        assertEquals(Voice, backOnce.step)
        assertEquals(backOnce, backOnce.back(Language))
    }

    @Test
    fun `a page opened with Change returns to Review when finished or on Back`() {
        val changing = atReview.change(Workspace)
        assertEquals(changing, changing.change(Privacy))

        val finished = changing.finishSection(Workspace, Privacy)
        assertEquals(atReview, finished)
        assertEquals(finished, finished.finishSection(Workspace, Privacy))

        assertEquals(atReview, changing.back(Workspace))
    }

    @Test
    fun `a changed voice section returns to Review after its last page`() {
        val voiceDone = atReview.change(VoiceEngine)
            .advance(VoiceEngine, Voice)
            .advance(Voice, Language)
            .advance(Language, TestVoice)
            .finishSection(TestVoice, Workspace)

        assertEquals(atReview, voiceDone)
    }
}
