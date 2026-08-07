package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    fun fieldFontScaleIsLargerForBiggerFieldsAndClamped() {
        assertEquals(1f, fieldFontScale(1, 1))
        assertTrue(fieldFontScale(2, 2) > fieldFontScale(1, 1))
        assertEquals(2f, fieldFontScale(10, 10))
        assertEquals(1f, fieldFontScale(0, 0))
        assertTrue(fieldFontScale(4, 4) in 1f..2f)
    }
}
