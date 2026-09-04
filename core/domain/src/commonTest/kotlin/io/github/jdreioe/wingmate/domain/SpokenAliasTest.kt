package io.github.jdreioe.wingmate.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SpokenAliasTest {
    @Test
    fun textAliasesReplaceWholeWordsCaseInsensitively() {
        val dictionary = listOf(PronunciationEntry("AAC", "ay ay see"))
        assertEquals("I use ay ay see daily", applySpokenAliases("I use aac daily", dictionary))
    }

    @Test
    fun longerWordsWinSoShorterEntriesCannotSplitThem() {
        val dictionary = listOf(
            PronunciationEntry("car", "kar"),
            PronunciationEntry("carpet", "car pet"),
        )
        assertEquals("car pet and kar", applySpokenAliases("carpet and car", dictionary))
    }

    @Test
    fun phoneticAlphabetsAreLeftForSsmlEngines() {
        val dictionary = listOf(PronunciationEntry("tomato", "təˈmɑːtoʊ", "ipa"))
        assertEquals("tomato", applySpokenAliases("tomato", dictionary))
    }

    @Test
    fun blankEntriesAndAnEmptyDictionaryLeaveTheTextAlone() {
        assertEquals("hello", applySpokenAliases("hello", emptyList()))
        assertEquals(
            "hello",
            applySpokenAliases("hello", listOf(PronunciationEntry("hello", " ".trim()), PronunciationEntry("", "x"))),
        )
    }

    @Test
    fun aliasesAreNotTreatedAsReplacementTemplates() {
        val dictionary = listOf(PronunciationEntry("cost", "\$1 and \\n"))
        assertEquals("\$1 and \\n", applySpokenAliases("cost", dictionary))
    }
}
