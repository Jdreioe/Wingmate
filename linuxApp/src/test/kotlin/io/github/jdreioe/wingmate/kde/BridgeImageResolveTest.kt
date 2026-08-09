package io.github.jdreioe.wingmate.kde

import io.github.jdreioe.wingmate.domain.obf.ObfImage
import io.github.jdreioe.wingmate.domain.obf.ObfSymbol
import io.github.jdreioe.wingmate.domain.obf.obfImageSources
import io.github.jdreioe.wingmate.domain.obf.resolveObfImageSource
import io.github.jdreioe.wingmate.domain.obf.ObfImageSource
import io.github.jdreioe.wingmate.domain.obf.ObfMediaSource
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class BridgeImageResolveTest {
    private val pngPrefix = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A,
    )

    @Test
    fun `shared priority order is data then dataUrl then path then url then symbol`() {
        val image = ObfImage(
            id = "i",
            data = "AAA=AAAAAAAAAA",
            dataUrl = "data:image/png;base64,bbbb",
            path = "images/x.png",
            url = "https://8.8.8.8/x.png",
            symbol = ObfSymbol(set = "opensymbols", filename = "cat.svg", libraryKey = "arasaac"),
        )
        val sources = obfImageSources(image)
        assertEquals(ObfMediaSource.Data::class, sources[0]::class)
        assertEquals(ObfMediaSource.Url::class, sources[1]::class)
        assertEquals(ObfMediaSource.Path::class, sources[2]::class)
        assertEquals(ObfMediaSource.Url::class, sources[3]::class)
        assertEquals(ObfMediaSource.Symbol::class, sources[4]::class)
        assertEquals(ObfImageSource.DataUri::class, resolveObfImageSource(image)::class)
    }

    @Test
    fun `data uri and raw base64 both decode`() {
        val raw = Base64.getEncoder().encodeToString(pngPrefix)
        val fromRaw = decodeInlineImage(raw)
        assertNotNull(fromRaw)
        assertEquals(pngPrefix.toList(), fromRaw.first.toList())
        assertEquals(null, fromRaw.second)

        val dataUri = "data:image/png;base64,$raw"
        val fromDataUri = decodeInlineImage(dataUri)
        assertNotNull(fromDataUri)
        assertEquals(pngPrefix.toList(), fromDataUri.first.toList())
        assertEquals("image/png", fromDataUri.second)
    }

    @Test
    fun `garbage inline payloads do not resolve`() {
        assertEquals(null, decodeInlineImage(""))
        assertEquals(null, decodeInlineImage("!!not-base64!!"))
    }

    @Test
    fun `data source renders without touching the network or filesystem`() {
        val raw = Base64.getEncoder().encodeToString(pngPrefix)
        val resolved = resolveObfImageBytes(ObfImage(id = "i", data = raw))
        assertNotNull(resolved)
        assertEquals("image/png", resolved.second)
        assertEquals(pngPrefix.toList(), resolved.first.toList())
    }

    @Test
    fun `image with no source does not resolve`() {
        assertEquals(null, resolveObfImageBytes(ObfImage(id = "empty")))
    }
}