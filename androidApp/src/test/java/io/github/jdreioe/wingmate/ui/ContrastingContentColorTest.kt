package io.github.jdreioe.wingmate.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class ContrastingContentColorTest {
    @Test
    fun aFaintTranslucentColourOnALightPageGetsDarkText() {
        // "+less" in the Lots of Stuff sample: rgba(0, 0, 0, 0.1) reads as light grey on a light page.
        assertEquals(Color.Black, contrastingContentColor(parseHexToColor("rgba(0, 0, 0, 0.1)"), backdrop = Color.White))
    }

    @Test
    fun theSameColourOnADarkPageGetsLightText() {
        assertEquals(Color.White, contrastingContentColor(parseHexToColor("rgba(0, 0, 0, 0.1)"), backdrop = Color(0xFF111318)))
    }

    @Test
    fun opaqueColoursAreUnchanged() {
        assertEquals(Color.White, contrastingContentColor(Color(0xFF1E3A8A)))
        assertEquals(Color.Black, contrastingContentColor(Color(0xFFFFF3B0)))
    }
}
