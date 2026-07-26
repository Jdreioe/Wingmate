package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.obf.BoardActivationBehavior
import io.github.jdreioe.wingmate.domain.obf.BoardReturnBehavior
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BoardSettingsBehaviorTest {

    @Test
    fun activationModesChooseSpeechAndMessageIndependently() {
        assertTrue(shouldSpeakBoardSelection(BoardActivationBehavior.SpeakAndAdd))
        assertTrue(shouldAddBoardSelection(BoardActivationBehavior.SpeakAndAdd))

        assertFalse(shouldSpeakBoardSelection(BoardActivationBehavior.AddOnly))
        assertTrue(shouldAddBoardSelection(BoardActivationBehavior.AddOnly))

        assertTrue(shouldSpeakBoardSelection(BoardActivationBehavior.SpeakOnly))
        assertFalse(shouldAddBoardSelection(BoardActivationBehavior.SpeakOnly))
    }

    @Test
    fun stayKeepsCurrentPageAndStack() {
        assertEquals(
            "food" to listOf("home"),
            applyBoardReturnBehavior(
                BoardReturnBehavior.Stay,
                currentBoardId = "food",
                boardStack = listOf("home"),
                rootBoardId = "home"
            )
        )
    }

    @Test
    fun previousPopsOnePageAndDoesNothingWithAnEmptyStack() {
        assertEquals(
            "category" to listOf("home"),
            applyBoardReturnBehavior(
                BoardReturnBehavior.Previous,
                currentBoardId = "food",
                boardStack = listOf("home", "category"),
                rootBoardId = "home"
            )
        )
        assertEquals(
            "home" to emptyList(),
            applyBoardReturnBehavior(
                BoardReturnBehavior.Previous,
                currentBoardId = "home",
                boardStack = emptyList(),
                rootBoardId = "home"
            )
        )
    }

    @Test
    fun startPageClearsTheNavigationStack() {
        assertEquals(
            "home" to emptyList(),
            applyBoardReturnBehavior(
                BoardReturnBehavior.StartPage,
                currentBoardId = "food",
                boardStack = listOf("home", "category"),
                rootBoardId = "home"
            )
        )
    }
}
