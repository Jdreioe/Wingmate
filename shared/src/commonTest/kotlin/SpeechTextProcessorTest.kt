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
}
