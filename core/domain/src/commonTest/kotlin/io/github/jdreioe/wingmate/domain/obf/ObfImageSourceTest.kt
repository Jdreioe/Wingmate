package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals

class ObfImageSourceTest {

    @Test
    fun blankFieldsAreIgnored() {
        val symbol = ObfSymbol(set = "pcs", filename = "yes.png")
        val image = ObfImage(id = "1", data = "   ", path = "", url = null, symbol = symbol)
        assertEquals(listOf(ObfMediaSource.Symbol(symbol)), obfImageSources(image))
    }

    @Test
    fun noCandidatesWhenEmpty() {
        assertEquals(emptyList(), obfImageSources(null))
        assertEquals(emptyList(), obfImageSources(ObfImage(id = "1")))
    }

    @Test
    fun imageAndSoundExposeEquivalentOrderedFallbacks() {
        val image = ObfImage(
            id = "image", data = "inline", dataUrl = "https://data",
            path = "media/path", url = "https://url", symbol = ObfSymbol(set = "set")
        )
        val sound = ObfSound(
            id = "sound", data = "inline", dataUrl = "https://data",
            path = "media/path", url = "https://url"
        )
        assertEquals(
            listOf("Data", "Url", "Path", "Url", "Symbol"),
            obfImageSources(image).map { it::class.simpleName }
        )
        assertEquals(
            listOf("Data", "Url", "Path", "Url"),
            obfSoundSources(sound).map { it::class.simpleName }
        )
    }
}
