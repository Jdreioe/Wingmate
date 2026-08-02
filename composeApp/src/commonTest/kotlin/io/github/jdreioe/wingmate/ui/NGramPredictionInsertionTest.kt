package io.github.jdreioe.wingmate.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class NGramPredictionInsertionTest {
    @Test
    fun completesTheCurrentWordOrAddsANewOne() {
        assertEquals("lo", nGramPredictionInsertion("hel", "hello"))
        assertEquals("world", nGramPredictionInsertion("hello ", "world"))
        assertEquals(" world", nGramPredictionInsertion("hello", "world"))
    }
}
