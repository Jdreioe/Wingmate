package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals

class ColorUtilsTest {
    @Test
    fun darkFieldColorsUseWhiteText() {
        assertEquals(Color.White, contrastingContentColor(Color(0xFF000000)))
        assertEquals(Color.White, contrastingContentColor(Color(0xFF17365D)))
    }

    @Test
    fun lightFieldColorsUseBlackText() {
        assertEquals(Color.Black, contrastingContentColor(Color(0xFFFFFFFF)))
        assertEquals(Color.Black, contrastingContentColor(Color(0xFFFFF176)))
        assertEquals(Color.Black, contrastingContentColor(Color(0xFF81D4FA)))
    }
}
