package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SaidText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimpleNGramPredictionServiceTest {
    @Test
    fun trigramContextOutranksMoreFrequentFallbacks() = runBlocking {
        val service = trainedWith(
            "I would like tea",
            "I would like tea",
            "we all like cake",
            "we all like cake",
            "we all like cake",
            "I would love music"
        )

        assertEquals("tea", service.predict("I would like ", 3, 0).words.first())
    }

    @Test
    fun prefixFiltersContextualCandidatesAndIgnoresCase() = runBlocking {
        val service = trainedWith("please drink water", "please drink wine", "walk home")

        val words = service.predict("please drink W", 5, 0).words

        assertEquals(listOf("Water", "Wine"), words.take(2))
        assertTrue(words.all { it.startsWith("W") })
    }

    @Test
    fun casingVariantsDoNotCreateDuplicateSuggestions() = runBlocking {
        val service = trainedWith("Hello there", "hello friend", "HELLO again")

        val words = service.predict("he", 5, 0).words

        assertEquals(1, words.count { it.equals("hello", ignoreCase = true) })
        assertEquals("hello", words.first { it.equals("hello", ignoreCase = true) })
    }

    @Test
    fun punctuationEndsAContextInsteadOfLearningAcrossSentences() = runBlocking {
        val service = trainedWith("drink water. Go home", "drink water. Stay here")

        val afterSentence = service.predict("drink water. ", 5, 0).words

        // If sentence boundaries had been trained as transitions, Go and Stay
        // would receive a bigram boost and lead this result.
        assertFalse(afterSentence.first() in setOf("Go", "Stay"))
    }

    @Test
    fun commaStillUsesPreviousWordAsContext() = runBlocking {
        val service = trainedWith("hello, friend", "hello, friend", "other words")

        assertEquals("friend", service.predict("hello, ", 1, 0).words.single())
    }

    @Test
    fun letterPredictionUsesLongerContextFirst() = runBlocking {
        val service = trainedWith("cart cart cart", "can can", "dog")

        val result = service.predict("ca", 0, 3)

        assertEquals('r', result.letters.first())
        assertTrue(result.words.isEmpty())
    }

    @Test
    fun negativeLimitsAreTreatedAsEmptyRequests() = runBlocking {
        val service = trainedWith("hello world")

        assertEquals(emptyList(), service.predict("hel", -1, -1).words)
        assertEquals(emptyList(), service.predict("hel", -1, -1).letters)
    }

    private suspend fun trainedWith(vararg phrases: String): SimpleNGramPredictionService {
        return SimpleNGramPredictionService().also { service ->
            service.train(phrases.map { SaidText(saidText = it) })
        }
    }
}
