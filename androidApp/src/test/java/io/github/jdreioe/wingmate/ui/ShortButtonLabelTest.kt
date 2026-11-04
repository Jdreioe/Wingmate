package io.github.jdreioe.wingmate.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortButtonLabelTest {
    @Test
    fun wordsAndShortPhrasesAreShortLabels() {
        listOf("I", "Kernetavle", "all done", "Hvordan går det?", "Vær så venlig", "Thank you").forEach {
            assertTrue(it, isShortButtonLabel(it))
        }
    }

    @Test
    fun sentencesAreNot() {
        listOf("I need a break, please.", "Hi, I'm Alex. I use this voice to talk.", "Can you help me now?").forEach {
            assertFalse(it, isShortButtonLabel(it))
        }
    }
}
