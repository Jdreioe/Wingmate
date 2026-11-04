package io.github.jdreioe.wingmate.ui

import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.Voice
import org.junit.Assert.assertEquals
import org.junit.Test

class SecondaryLanguageTest {
    private val danishAndEnglish = Voice(name = "multi", supportedLanguages = listOf("da-DK", "en-US"))

    @Test
    fun `a second language applies only when the voice speaks it and it differs from the first`() {
        assertEquals("en-US", Settings(primaryLanguage = "da-DK", secondaryLanguage = "en-US").secondaryLanguageFor(danishAndEnglish))
        assertEquals(null, Settings(primaryLanguage = "da-DK", secondaryLanguage = "fr-FR").secondaryLanguageFor(danishAndEnglish))
        assertEquals(null, Settings(primaryLanguage = "da-DK", secondaryLanguage = "da-DK").secondaryLanguageFor(danishAndEnglish))
        assertEquals(null, Settings(primaryLanguage = "da-DK", secondaryLanguage = "en-US").secondaryLanguageFor(Voice(name = "one", supportedLanguages = listOf("da-DK"))))
    }
}
