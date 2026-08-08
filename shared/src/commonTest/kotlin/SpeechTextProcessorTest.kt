import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechTextProcessorTest {

    @Test
    fun normalizeLanguageShortTag_enToEnUs() {
        val input = "This is <en>outdated</en> technology."

        val normalized = SpeechTextProcessor.normalizeShorthandSsml(input)

        assertEquals(
            "This is <lang xml:lang=\"en-US\">outdated</lang> technology.",
            normalized
        )
    }

    @Test
    fun normalizeBreakShortTag_secondsToBreakTag() {
        val input = "Start <2s> end"

        val normalized = SpeechTextProcessor.normalizeShorthandSsml(input)

        assertEquals("Start <break time=\"2s\"/> end", normalized)
    }

    @Test
    fun normalizeMixedDanishEnglishExample() {
        val input = "Hej, jeg hedder Jonas, og det er <en> outdated </en> teknologi. <2s>"

        val normalized = SpeechTextProcessor.normalizeShorthandSsml(input)

        assertTrue(normalized.contains("<lang xml:lang=\"en-US\"> outdated </lang>"))
        assertTrue(normalized.contains("<break time=\"2s\"/>"))
    }

    @Test
    fun normalizeKnownShortTag_daToDaDk() {
        val input = "<da>Hej med dig</da>"

        val normalized = SpeechTextProcessor.normalizeShorthandSsml(input)

        assertEquals("<lang xml:lang=\"da-DK\">Hej med dig</lang>", normalized)
    }

    @Test
    fun unknownShortTagRemainsUntouched() {
        val input = "<zz>Hello</zz>"

        val normalized = SpeechTextProcessor.normalizeShorthandSsml(input)

        assertEquals("<zz>Hello</zz>", normalized)
    }

    @Test
    fun processText_extractsLanguageTagAndStripsWrapper() {
        val segments = SpeechTextProcessor.processText("Start <en>hello</en> end")

        assertEquals(1, segments.size)
        assertEquals("en-US", segments[0].languageTag)
        assertEquals("Start hello end", segments[0].text)
    }

    @Test
    fun processText_splitsOnPauseAndKeepsLanguageTag() {
        val segments = SpeechTextProcessor.processText("One <2s> <da>to</da>")

        assertEquals(2, segments.size)
        assertEquals("One", segments[0].text)
        assertEquals(2000, segments[0].pauseDurationMs)
        assertEquals("to", segments[1].text)
        assertEquals("da-DK", segments[1].languageTag)
    }

    @Test
    fun processText_noTagsReturnsSingleSegment() {
        val segments = SpeechTextProcessor.processText("Just plain text")

        assertEquals(1, segments.size)
        assertEquals("Just plain text", segments[0].text)
        assertEquals(null, segments[0].languageTag)
        assertEquals(0, segments[0].pauseDurationMs)
    }
}
