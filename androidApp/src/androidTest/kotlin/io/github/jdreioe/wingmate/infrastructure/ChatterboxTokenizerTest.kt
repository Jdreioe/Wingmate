package io.github.jdreioe.wingmate.infrastructure

import org.junit.Test
import org.junit.Assert.*
import java.io.File

class ChatterboxTokenizerTest {

    private fun createTempVocab(content: String): File {
        val f = File.createTempFile("chatterbox_vocab_", ".json")
        f.writeText(content)
        f.deleteOnExit()
        return f
    }

    @Test
    fun textToTokens_encodesKnownTokens() {
        val vocab = """{"hello": 10, "world": 20, "<unk>": 0}"""
        val tokenizer = ChatterboxTokenizer(createTempVocab(vocab).absolutePath)
        val tokens = tokenizer.textToTokens("hello world")
        assertTrue("hello" in tokens.map { tokenizer.tokenToText(it) })
        assertTrue("world" in tokens.map { tokenizer.tokenToText(it) })
    }

    @Test
    fun textToTokens_caseInsensitive() {
        val vocab = """{"hello": 10, "<unk>": 0}"""
        val tokenizer = ChatterboxTokenizer(createTempVocab(vocab).absolutePath)
        val tokens = tokenizer.textToTokens("HELLO")
        assertEquals(1, tokens.size)
        assertEquals(10, tokens[0])
    }

    @Test
    fun textToTokens_unknownTokenBecomesUnk() {
        val vocab = """{"<unk>": 0}"""
        val tokenizer = ChatterboxTokenizer(createTempVocab(vocab).absolutePath)
        val tokens = tokenizer.textToTokens("zzzzz")
        assertTrue(tokens.all { it == 0 })
    }

    @Test
    fun textToTokens_emptyString() {
        val vocab = """{"<unk>": 0}"""
        val tokenizer = ChatterboxTokenizer(createTempVocab(vocab).absolutePath)
        val tokens = tokenizer.textToTokens("")
        assertTrue(tokens.isEmpty())
    }

    @Test
    fun textToTokens_longestMatchWins() {
        val vocab = """{"ab": 1, "abc": 2, "d": 3, "<unk>": 0}"""
        val tokenizer = ChatterboxTokenizer(createTempVocab(vocab).absolutePath)
        val tokens = tokenizer.textToTokens("abcd")
        assertEquals(listOf(2, 3), tokens)
    }

    @Test
    fun tokenToText_knownId() {
        val vocab = """{"hello": 10, "<unk>": 0}"""
        val tokenizer = ChatterboxTokenizer(createTempVocab(vocab).absolutePath)
        assertEquals("hello", tokenizer.tokenToText(10))
    }

    @Test
    fun tokenToText_unknownId() {
        val vocab = """{"<unk>": 0}"""
        val tokenizer = ChatterboxTokenizer(createTempVocab(vocab).absolutePath)
        assertEquals("<unk>", tokenizer.tokenToText(999))
    }

    @Test
    fun constantsAreCorrect() {
        assertEquals(255, ChatterboxTokenizer.SOT_TEXT_ID)
        assertEquals(0, ChatterboxTokenizer.EOT_TEXT_ID)
        assertEquals(6561, ChatterboxTokenizer.SOT_SPEECH)
        assertEquals(6562, ChatterboxTokenizer.EOT_SPEECH)
        assertEquals(8194, ChatterboxTokenizer.SPEECH_VOCAB)
        assertEquals(256, ChatterboxTokenizer.MAX_TEXT_LEN)
    }

    @Test
    fun textToTokens_multipleMatchesAcrossString() {
        val vocab = """{"a": 1, "b": 2, "ab": 3, "bc": 4, "<unk>": 0}"""
        val tokenizer = ChatterboxTokenizer(createTempVocab(vocab).absolutePath)
        val tokens = tokenizer.textToTokens("abc")
        assertEquals(listOf(3, 2), tokens)
    }

    @Test
    fun textToTokens_handlesNewlinesAndWhitespace_inVocab() {
        val vocab = """{" ": 5, "\\n": 6, "a": 1, "<unk>": 0}"""
        val raw = """{" ": 5, "\n": 6, "a": 1, "<unk>": 0}"""
        val tokenizer = ChatterboxTokenizer(createTempVocab(raw).absolutePath)
        val tokens = tokenizer.textToTokens("a a")
        assertTrue(tokens.size >= 2)
    }
}
