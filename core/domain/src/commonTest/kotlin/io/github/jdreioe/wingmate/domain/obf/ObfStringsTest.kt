package io.github.jdreioe.wingmate.domain.obf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ObfStringsTest {

    private val strings = mapOf(
        "da" to mapOf(
            "Hello" to "Hej",
            "Food" to "Mad"
        ),
        "en" to mapOf(
            "Hello" to "Hello",
            "Food" to "Food"
        ),
        "da-DK" to mapOf(
            "Morning" to "Godmorgen"
        )
    )

    @Test
    fun exactLocaleMatch() {
        assertEquals("Godmorgen", resolveObfLocalizedString(strings, "da-DK", "Morning"))
    }

    @Test
    fun generalLocaleFallback() {
        assertEquals("Hej", resolveObfLocalizedString(strings, "da-DK", "Hello"))
    }

    @Test
    fun directGeneralMatch() {
        assertEquals("Hej", resolveObfLocalizedString(strings, "da", "Hello"))
    }

    @Test
    fun fallsBackToRawValue() {
        assertEquals("Goodbye", resolveObfLocalizedString(strings, "da-DK", "Goodbye"))
    }

    @Test
    fun nullLocaleReturnsRaw() {
        assertEquals("Hello", resolveObfLocalizedString(strings, null, "Hello"))
    }

    @Test
    fun nullRawReturnsNull() {
        assertNull(resolveObfLocalizedString(strings, "da", null))
    }

    @Test
    fun emptyLocaleReturnsRaw() {
        assertEquals("Hello", resolveObfLocalizedString(strings, "  ", "Hello"))
    }

    @Test
    fun emptyStringsMap() {
        assertEquals("Hi", resolveObfLocalizedString(emptyMap(), "da", "Hi"))
    }
}
