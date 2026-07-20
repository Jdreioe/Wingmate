package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ObfImageSourceTest {

    @Test
    fun prefersDataOverPathUrlAndSymbol() {
        val image = ObfImage(
            id = "1",
            data = "data:image/png;base64,abc",
            path = "images/a.png",
            url = "https://example.com/a.png",
            symbol = ObfSymbol(set = "symbolstix", filename = "happy.png")
        )
        assertEquals(ObfImageSource.DataUri("data:image/png;base64,abc"), resolveObfImageSource(image))
    }

    @Test
    fun prefersPathOverUrlAndSymbol() {
        val image = ObfImage(
            id = "1",
            path = "images/a.png",
            url = "https://example.com/a.png",
            symbol = ObfSymbol(set = "symbolstix", filename = "happy.png")
        )
        assertEquals(ObfImageSource.Path("images/a.png"), resolveObfImageSource(image))
    }

    @Test
    fun prefersUrlOverSymbol() {
        val image = ObfImage(
            id = "1",
            url = "https://example.com/a.png",
            symbol = ObfSymbol(set = "symbolstix", filename = "happy.png")
        )
        assertEquals(ObfImageSource.Url("https://example.com/a.png"), resolveObfImageSource(image))
    }

    @Test
    fun fallsBackToSymbol() {
        val symbol = ObfSymbol(set = "symbolstix", filename = "happy.png")
        val image = ObfImage(id = "1", symbol = symbol)
        assertEquals(ObfImageSource.Symbol(symbol), resolveObfImageSource(image))
    }

    @Test
    fun blankFieldsAreIgnored() {
        val image = ObfImage(
            id = "1",
            data = "   ",
            path = "",
            url = null,
            symbol = ObfSymbol(set = "pcs", filename = "yes.png")
        )
        val source = resolveObfImageSource(image)
        assertTrue(source is ObfImageSource.Symbol)
        assertEquals("pcs", (source as ObfImageSource.Symbol).symbol.set)
    }

    @Test
    fun noneWhenEmpty() {
        assertEquals(ObfImageSource.None, resolveObfImageSource(null))
        assertEquals(ObfImageSource.None, resolveObfImageSource(ObfImage(id = "1")))
    }
}
