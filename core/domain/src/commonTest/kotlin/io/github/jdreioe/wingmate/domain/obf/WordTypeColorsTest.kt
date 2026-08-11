package io.github.jdreioe.wingmate.domain.obf

import io.github.jdreioe.wingmate.domain.WordTypeColorScheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WordTypeColorsTest {
    @Test
    fun classifiesSupportedLanguagesAndLeavesUnknownLanguagesUncolored() {
        assertEquals(WordType.Verb, inferWordType("go", "en-US"))
        assertEquals(WordType.Noun, inferWordType("vand", "da-DK"))
        assertNull(inferWordType("boire", "fr-FR"))
        assertNull(inferWordType("madeupword", "en-US"))
    }

    @Test
    fun manualWordTypeOverridesInferenceAndRoundTripsThroughExtension() {
        val corrected = ObfButton(id = "ambiguous", label = "play").withWordType(WordType.Noun)
        assertEquals(WordType.Noun, corrected.wordType)
        assertEquals(WordType.Noun, corrected.resolvedWordType("en-US"))
        assertNull(corrected.withWordType(null).wordType)
    }

    @Test
    fun explicitColorOverridesGeneratedColorAndSchemeCanBeDisabled() {
        val automatic = ObfButton(id = "verb", label = "go")
        assertEquals("#F48FB1", automatic.resolvedBackgroundColor(WordTypeColorScheme.Fitzgerald, "en-US"))
        assertNull(automatic.resolvedBackgroundColor(WordTypeColorScheme.None, "en-US"))
        assertEquals(
            "#123456",
            automatic.copy(backgroundColor = "#123456")
                .resolvedBackgroundColor(WordTypeColorScheme.Fitzgerald, "en-US")
        )
    }

    @Test
    fun generatedPaletteMeetsNormalTextContrastAgainstBlack() {
        WordType.entries.forEach { type ->
            assertTrue(
                contrastRatio("#000000", type.fitzgeraldColor) >= 4.5,
                "${type.name} ${type.fitzgeraldColor} must meet WCAG AA"
            )
        }
    }
}
