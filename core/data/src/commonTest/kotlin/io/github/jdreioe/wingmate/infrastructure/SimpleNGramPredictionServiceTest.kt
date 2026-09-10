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

    @Test
    fun singleLetterFindsWordsOutsideTheGlobalFrequencyCache() = runBlocking {
        val service = SimpleNGramPredictionService()
        val frequentWords = (0 until 600).map { index ->
            val suffix = "${('a'.code + index / 26).toChar()}${('a'.code + index % 26).toChar()}"
            "common$suffix" to 200
        }
        service.setBaseLanguage(frequentWords + ("xylophone" to 1))

        assertEquals(listOf("xylophone"), service.predict("x", 5, 0).words)
    }

    @Test
    fun letterSuggestionsPreferCompletionsOfTheWholePrefix() = runBlocking {
        val service = trainedWith("care", "party party party party party", "cargo")

        assertEquals(listOf('e', 'g'), service.predict("car", 0, 3).letters)
    }

    @Test
    fun unknownPrefixesStillGetLetterNGramFallbacks() = runBlocking {
        val service = trainedWith("care")

        assertEquals(listOf('e'), service.predict("zzar", 0, 3).letters)
    }

    @Test
    fun completeWordsDoNotBlockLongerLetterCompletions() = runBlocking {
        val service = trainedWith("car car car", "care", "cargo cargo")

        assertEquals(listOf('g', 'e'), service.predict("CAR", 0, 3).letters)
    }

    @Test
    fun precedingWordsGuideBothWordAndLetterSuggestions() = runBlocking {
        val service = trainedWith("please drink tea", "we enjoy toast toast toast toast toast")

        val result = service.predict("please drink t", 5, 3)

        assertEquals("tea", result.words.first())
        assertEquals('e', result.letters.first())
        assertEquals(result.letters, service.predict("please drink t", 0, 3).letters)
    }

    @Test
    fun lettersAfterASpaceComeFromContextualNextWords() = runBlocking {
        val service = trainedWith("please drink water", "eat toast toast toast toast")

        val result = service.predict("please drink ", 1, 3)

        assertEquals(listOf("water"), result.words)
        assertEquals('w', result.letters.first())
        assertEquals(result.letters, service.predict("please drink ", 5, 3).letters)
    }

    private suspend fun trainedWith(vararg phrases: String): SimpleNGramPredictionService {
        return SimpleNGramPredictionService().also { service ->
            service.train(phrases.map { SaidText(saidText = it) })
        }
    }
}
