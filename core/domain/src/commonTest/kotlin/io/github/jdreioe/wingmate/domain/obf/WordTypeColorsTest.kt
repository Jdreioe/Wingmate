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
    fun classifiesEnglishAndDanishQuestionWordsAsQuestion() {
        listOf("who", "what", "where", "when", "why", "how").forEach { word ->
            assertEquals(WordType.Question, inferWordType(word, "en-US"), "English '$word' should infer Question")
        }
        listOf("hvem", "hvad", "hvor", "hvornår", "hvorfor", "hvordan").forEach { word ->
            assertEquals(WordType.Question, inferWordType(word, "da-DK"), "Danish '$word' should infer Question")
        }
    }

    @Test
    fun classifiesCommonDanishCoreWords() {
        assertEquals(WordType.Other, inferWordType("ikke", "da-DK"))
        assertEquals(WordType.Other, inferWordType("igen", "da-DK"))
        assertEquals(WordType.Verb, inferWordType("stop", "da-DK"))
        assertEquals(WordType.Descriptor, inferWordType("færdig", "da-DK"))
        assertEquals(WordType.Other, inferWordType("her", "da-DK"))
        assertEquals(WordType.Other, inferWordType("der", "da-DK"))
        assertEquals(WordType.Other, inferWordType("også", "da-DK"))
    }

    @Test
    fun manualWordTypeOverridesInferenceAndRoundTripsThroughExtension() {
        val corrected = ObfButton(id = "ambiguous", label = "play").withWordType(WordType.Noun)
        assertEquals(WordType.Noun, corrected.wordType)
        assertEquals(WordType.Noun, corrected.resolvedWordType("en-US"))
        assertNull(corrected.withWordType(null).wordType)

        val question = ObfButton(id = "q", label = "custom").withWordType(WordType.Question)
        assertEquals(WordType.Question, question.wordType)
        assertEquals("question", question.wordType?.wireValue)
    }

    @Test
    fun paletteMatchesModifiedFitzgeraldKey() {
        assertEquals("#FFF176", WordType.Pronoun.fitzgeraldColor)
        assertEquals("#A5D6A7", WordType.Verb.fitzgeraldColor)
        assertEquals("#FFB74D", WordType.Noun.fitzgeraldColor)
        assertEquals("#81D4FA", WordType.Descriptor.fitzgeraldColor)
        assertEquals("#E0E0E0", WordType.Other.fitzgeraldColor)
        assertEquals("#CE93D8", WordType.Question.fitzgeraldColor)
        assertEquals("#F48FB1", WordType.Social.fitzgeraldColor)
    }

    @Test
    fun explicitColorOverridesGeneratedColorAndSchemeCanBeDisabled() {
        val automatic = ObfButton(id = "verb", label = "go")
        assertEquals("#A5D6A7", automatic.resolvedBackgroundColor(WordTypeColorScheme.Fitzgerald, "en-US"))
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
