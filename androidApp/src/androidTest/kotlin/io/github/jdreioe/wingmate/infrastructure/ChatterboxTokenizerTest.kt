package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxError
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.File

class ChatterboxTokenizerTest {
    private fun tokenizer(): ChatterboxTokenizer {
        val json = """
            {
              "model": {
                "type": "BPE",
                "vocab": {
                  "[STOP]":0,"[UNK]":1,"[SPACE]":2,".":9,"e":18,"j":23,"l":25,"o":28,
                  "H":284,"ll":84,"[en]":708,"[da]":715,"<EXAGGERATION>":6563,
                  "<START_SPEECH>":6561,"[START]":255
                },
                "merges": ["l l"]
              },
              "added_tokens": [
                {"id":0,"content":"[STOP]"},
                {"id":1,"content":"[UNK]"},
                {"id":2,"content":"[SPACE]"},
                {"id":255,"content":"[START]"},
                {"id":708,"content":"[en]"},
                {"id":715,"content":"[da]"},
                {"id":6561,"content":"<START_SPEECH>"},
                {"id":6563,"content":"<EXAGGERATION>"}
              ]
            }
        """.trimIndent()
        val file = File.createTempFile("tokenizer", ".json").apply { writeText(json) }
        return ChatterboxTokenizer(file.absolutePath)
    }

    @Test
    fun englishEncodingPreservesCaseAndTemplateTokens() {
        val encoding = tokenizer().encode("Hello.", "en")
        assertArrayEquals(
            longArrayOf(6563, 255, 708, 284, 18, 84, 28, 9, 0, 6561, 6561),
            encoding.inputIds,
        )
        assertArrayEquals(longArrayOf(0, 0, 1, 2, 3, 4, 5, 6, 7, 0, 0), encoding.positionIds)
    }

    @Test
    fun danishEncodingUsesDanishLanguageTokenAndSpaceToken() {
        val encoding = tokenizer().encode("Hej hej", "da")
        assertArrayEquals(
            longArrayOf(6563, 255, 715, 284, 18, 23, 2, 1, 18, 23, 0, 6561, 6561),
            encoding.inputIds,
        )
    }

    @Test
    fun unsupportedLanguageFailsClearly() {
        assertThrows(ChatterboxError.UnsupportedLanguage::class.java) {
            tokenizer().encode("Bonjour", "fr")
        }
    }

    @Test
    fun constantsMatchPinnedModel() {
        assertEquals(255L, ChatterboxTokenizer.SOT_TEXT_ID)
        assertEquals(0L, ChatterboxTokenizer.EOT_TEXT_ID)
        assertEquals(6561L, ChatterboxTokenizer.SOT_SPEECH)
        assertEquals(6562L, ChatterboxTokenizer.EOT_SPEECH)
        assertEquals(8194, ChatterboxTokenizer.SPEECH_VOCAB)
    }
}
