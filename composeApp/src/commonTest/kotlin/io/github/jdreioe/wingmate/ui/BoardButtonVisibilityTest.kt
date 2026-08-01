package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.obf.ObfButton
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardButtonVisibilityTest {
    private val hidden = ObfButton(id = "hidden", hidden = true)
    private val visible = ObfButton(id = "visible")

    @Test
    fun hiddenButtonRequiresEditModeOrSessionOverride() {
        assertFalse(isBoardButtonVisible(hidden, isEditMode = false, showHiddenButtons = false))
        assertTrue(isBoardButtonVisible(hidden, isEditMode = true, showHiddenButtons = false))
        assertTrue(isBoardButtonVisible(hidden, isEditMode = false, showHiddenButtons = true))
    }

    @Test
    fun ordinaryButtonIsAlwaysVisible() {
        assertTrue(isBoardButtonVisible(visible, isEditMode = false, showHiddenButtons = false))
    }

    @Test
    fun sessionOverrideActivatesPersistsAcrossBoardNavigationAndResets() {
        val session = HiddenButtonsSession()
        assertFalse(session.revealed)

        session.toggle()
        assertTrue(isBoardButtonVisible(hidden, isEditMode = false, showHiddenButtons = session.revealed))
        val hiddenOnAnotherBoard = ObfButton(id = "another", hidden = true)
        assertTrue(isBoardButtonVisible(hiddenOnAnotherBoard, isEditMode = false, showHiddenButtons = session.revealed))

        session.reset()
        assertFalse(session.revealed)
        assertEquals(true, hidden.hidden)
        assertFalse(HiddenButtonsSession().revealed)
    }
}
