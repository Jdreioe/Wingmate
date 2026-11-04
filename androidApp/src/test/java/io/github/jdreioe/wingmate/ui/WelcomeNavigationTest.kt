package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.ui.WelcomeStep.*
import org.junit.Assert.assertEquals
import org.junit.Test

class WelcomeNavigationTest {
    private val atReview = WelcomeNavigation(listOf(Start, VoiceEngine, Voice, TestVoice, Workspace, Privacy, Review))

    @Test
    fun `a repeated activation moves only once`() {
        val once = WelcomeNavigation(listOf(Start, VoiceEngine, Voice)).advance(Voice, TestVoice)
        assertEquals(once, once.advance(Voice, TestVoice))

        val backOnce = once.back(TestVoice)
        assertEquals(Voice, backOnce.step)
        assertEquals(backOnce, backOnce.back(TestVoice))
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
            .advance(Voice, TestVoice)
            .finishSection(TestVoice, Workspace)

        assertEquals(atReview, voiceDone)
    }

    @Test
    fun `entering a key again returns to the voice list instead of stacking another`() {
        val first = WelcomeNavigation(listOf(Start, VoiceEngine, AzureSetup)).toVoiceFrom(AzureSetup)
        assertEquals(listOf(Start, VoiceEngine, AzureSetup, Voice), first.path)

        val again = first.advance(Voice, AzureSetup).toVoiceFrom(AzureSetup)
        assertEquals(first, again)
    }

    @Test
    fun `language is a side page that returns where it was opened`() {
        val fromTest = WelcomeNavigation(listOf(Start, VoiceEngine, Voice, TestVoice)).advance(TestVoice, Language)
        assertEquals(TestVoice, fromTest.back(Language).step)

        assertEquals(atReview, atReview.change(Language).back(Language))
    }
}
