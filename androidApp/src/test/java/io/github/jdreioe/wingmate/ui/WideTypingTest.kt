package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WideTypingTest {
    @Test
    fun phonesKeepThePhoneLayoutInBothOrientations() {
        assertFalse(usesWideTyping(DpSize(411.dp, 891.dp)))
        // Landscape phones are wide enough but too short for the Message, Phrases and Speak together.
        assertFalse(usesWideTyping(DpSize(891.dp, 411.dp)))
    }

    @Test
    fun tabletsFoldablesAndLaptopWindowsUseTheWideLayout() {
        assertTrue(usesWideTyping(DpSize(1280.dp, 800.dp)))
        assertTrue(usesWideTyping(DpSize(800.dp, 1280.dp)))
        assertTrue(usesWideTyping(DpSize(841.dp, 701.dp)))
        assertTrue(usesWideTyping(DpSize(600.dp, 480.dp)))
    }
}
