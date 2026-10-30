package io.github.jdreioe.wingmate.domain.obf

import io.github.jdreioe.wingmate.domain.SpeechPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechScreenImportTest {
    @Test
    fun preservesDanishWordsPunctuationAndWrappedLinesWhileIgnoringHeadingText() {
        val pages = SpeechScreenImport.preview(
            "Min tale",
            "\uFEFFHej. Jeg hedder Jonas.\r\nOg det her er ikke min rigtige stemme.\r\n \r\n## Afslutning\r\nVores rigtige stemmer er vores egne ord.\r\n\r\nTak fordi I lyttede.",
            4,
        )
        assertEquals(listOf("Min tale", "Afslutning"), pages.map { it.name })
        assertEquals(listOf(
            "Hej. Jeg hedder Jonas.\nOg det her er ikke min rigtige stemme.",
            "Vores rigtige stemmer er vores egne ord.", "Tak fordi I lyttede.",
        ), pages.flatMap { it.paragraphs })
    }

    @Test
    fun repaginatesWithinSectionsWithoutDroppingRepeatedParagraphs() {
        val text = "First\n\nSame\n\nSame\n# Demo\nFourth\n\nFifth"
        val pages = SpeechScreenImport.preview("Speech", text, 2)
        assertEquals(listOf("Speech · 1", "Speech · 2", "Demo"), pages.map { it.name })
        assertEquals(listOf("First", "Same", "Same", "Fourth", "Fifth"), pages.flatMap { it.paragraphs })
        assertEquals(5, SpeechScreenImport.preview("Speech", text, 1).size)
    }

    @Test
    fun generatedScreenSpeaksWholeParagraphsAndHasSilentBidirectionalPageLinks() {
        val longParagraph = "Jeg hedder Jonas. " + "Det er mine egne ord. ".repeat(10).trim()
        val graph = SpeechScreenImport.createGraph(
            "speech", "Min tale", "$longParagraph\n\nAndet afsnit.\n\nTak.", 1, "Forrige", "Næste", 1L,
        )
        graph.requireValid()
        assertEquals(longParagraph, graph.boards.first().buttons.first().vocalization)
        assertTrue(graph.boards.first().buttons.first().label!!.length < longParagraph.length)
        assertEquals(BoardActivationBehavior.SpeakOnly, graph.boardSet.screenSettings.activationBehavior)
        assertEquals(SpeechPolicy.Immediate, graph.boardSet.screenSettings.speechPolicy)
        assertEquals(false, graph.boardSet.screenSettings.showMessageBar)
        graph.boards.forEachIndexed { index, board ->
            val paragraph = board.buttons.first()
            assertEquals(listOf(paragraph.id, paragraph.id), board.grid!!.order.first())
            val links = board.buttons.filter { it.loadBoard != null }
            assertEquals(listOfNotNull(
                graph.boards.getOrNull(index - 1)?.id, graph.boards.getOrNull(index + 1)?.id,
            ), links.map { it.loadBoard!!.id })
            links.forEach { assertNull(it.vocalization) }
        }
    }

    @Test
    fun emptyOrHeadingOnlyInputDoesNotCreateScreensAndInvalidPageSizesAreRejected() {
        assertTrue(SpeechScreenImport.preview("Speech", " \n# Heading\n", 4).isEmpty())
        for (size in listOf(0, 9)) {
            assertFailsWith<IllegalArgumentException> { SpeechScreenImport.preview("Speech", "Hello", size) }
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechScreenImport.createGraph("s", "Speech", "", 4, "Previous", "Next", 1)
        }
        assertFailsWith<IllegalArgumentException> {
            SpeechScreenImport.createGraph("s", "", "Hello", 4, "Previous", "Next", 1)
        }
    }

    @Test
    fun immediateSpeechOverrideRoundTripsWithoutChangingOrdinaryScreenDefaults() {
        val settings = BoardSettingsOverrides(speechPolicy = SpeechPolicy.Immediate)
        assertFalse(settings.isEmpty)
        assertEquals(settings, decodeBoardSettings(encodeBoardSettings(settings)))
        assertNull(resolveBoardSettings(true, true, false).speechPolicy)
        assertEquals(SpeechPolicy.Immediate, resolveBoardSettings(true, true, false, screen = settings).speechPolicy)
        assertEquals(SpeechPolicy.SentenceOnly, resolveBoardSettings(
            true, true, false, screen = settings, page = BoardSettingsOverrides(speechPolicy = SpeechPolicy.SentenceOnly),
        ).speechPolicy)
    }
}
