package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.PredictionResult
import io.github.jdreioe.wingmate.domain.SaidText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A small, local word predictor backed by unigram, bigram, and trigram counts.
 *
 * Words are stored under a canonical, lower-case key. This prevents differently
 * cased occurrences from becoming duplicate suggestions while still retaining
 * the most frequently seen spelling for display.
 */
class SimpleNGramPredictionService {
    private val mutex = Mutex()

    private companion object {
        private const val SHORT_PREFIX_CANDIDATE_LIMIT = 64
        private const val PREFIX_CANDIDATE_LIMIT = 128
        private const val FALLBACK_CANDIDATE_LIMIT = 128
        private const val TOP_WORD_CACHE_LIMIT = 512
        private const val USER_WEIGHT = 8

        private const val TRIGRAM_WEIGHT = 0.70
        private const val BIGRAM_WEIGHT = 0.25
        private const val UNIGRAM_WEIGHT = 0.05

        private val WORD_REGEX = Regex("[\\p{L}\\p{M}]+(?:['’\\-][\\p{L}\\p{M}]+)*")
        private val SENTENCE_BOUNDARY_REGEX = Regex("[.!?;\\n\\r]+")
    }

    // Every word in these maps is a canonical word key.
    private val bigramCounts = mutableMapOf<String, MutableMap<String, Int>>()
    private val trigramCounts = mutableMapOf<String, MutableMap<String, Int>>()
    private val letterBigramCounts = mutableMapOf<Char, MutableMap<Char, Int>>()
    private val letterTrigramCounts = mutableMapOf<String, MutableMap<Char, Int>>()
    private val wordsByPrefix = mutableMapOf<String, MutableSet<String>>()
    private val wordFrequency = mutableMapOf<String, Int>()

    // Preserve useful casing (for example "I") without allowing casing duplicates.
    private val surfaceCounts = mutableMapOf<String, MutableMap<String, Int>>()
    private val preferredSurface = mutableMapOf<String, String>()
    private var topFrequentWords: List<String> = emptyList()
    private var trained = false

    suspend fun train(history: List<SaidText>) = train(history, clear = true)

    suspend fun train(history: List<SaidText>, clear: Boolean) = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (clear) clearModel()
            history.forEach { entry ->
                entry.saidText?.let { trainOnText(it, USER_WEIGHT) }
            }
            finishTraining()
        }
    }

    /**
     * Replace the base vocabulary. Dictionary frequencies provide a useful
     * fallback, while the higher user weight lets personal vocabulary adapt
     * after only a few uses.
     */
    suspend fun setBaseLanguage(words: List<Pair<String, Int>>) = withContext(Dispatchers.Default) {
        mutex.withLock {
            clearModel()
            words.forEach { (surface, frequency) ->
                val token = tokenize(surface).singleOrNull() ?: return@forEach
                val weight = (frequency / 10).coerceAtLeast(1)
                addWord(token, weight)
            }
            finishTraining()
        }
    }

    private fun clearModel() {
        bigramCounts.clear()
        trigramCounts.clear()
        letterBigramCounts.clear()
        letterTrigramCounts.clear()
        wordsByPrefix.clear()
        wordFrequency.clear()
        surfaceCounts.clear()
        preferredSurface.clear()
        topFrequentWords = emptyList()
        trained = false
    }

    private fun finishTraining() {
        preferredSurface.clear()
        surfaceCounts.forEach { (canonical, variants) ->
            preferredSurface[canonical] = variants.entries
                .sortedWith(
                    compareByDescending<Map.Entry<String, Int>> { it.value }
                        .thenBy { surfacePreference(canonical, it.key) }
                        .thenBy { it.key }
                )
                .first().key
        }
        topFrequentWords = wordFrequency.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .asSequence()
            .map { it.key }
            .take(TOP_WORD_CACHE_LIMIT)
            .toList()
        trained = true
        OperationalLogger.debug("prediction_model.train", "succeeded", count = wordFrequency.size)
    }

    private fun trainOnText(text: String, weight: Int) {
        // Do not learn transitions across sentence boundaries.
        text.split(SENTENCE_BOUNDARY_REGEX).forEach { sentence ->
            val words = tokenize(sentence)
            words.forEach { addWord(it, weight) }

            for (index in 0 until words.lastIndex) {
                incrementNested(bigramCounts, canonical(words[index]), canonical(words[index + 1]), weight)
            }
            for (index in 0 until words.size - 2) {
                val context = "${canonical(words[index])} ${canonical(words[index + 1])}"
                incrementNested(trigramCounts, context, canonical(words[index + 2]), weight)
            }
        }
    }

    private fun addWord(surface: String, weight: Int) {
        val word = canonical(surface)
        if (word.isEmpty()) return

        wordFrequency[word] = (wordFrequency[word] ?: 0) + weight
        surfaceCounts.getOrPut(word) { mutableMapOf() }
            .let { it[surface] = (it[surface] ?: 0) + weight }
        for (length in 1..word.length) {
            wordsByPrefix.getOrPut(word.substring(0, length)) { mutableSetOf() }.add(word)
        }
        trainLetterNGrams(word, weight)
    }

    private fun trainLetterNGrams(word: String, weight: Int) {
        for (index in 0 until word.lastIndex) {
            incrementNested(letterBigramCounts, word[index], word[index + 1], weight)
        }
        for (index in 0 until word.length - 2) {
            incrementNested(letterTrigramCounts, word.substring(index, index + 2), word[index + 2], weight)
        }
    }

    private fun <K, V> incrementNested(
        counts: MutableMap<K, MutableMap<V, Int>>,
        context: K,
        value: V,
        amount: Int
    ) {
        counts.getOrPut(context) { mutableMapOf() }
            .let { it[value] = (it[value] ?: 0) + amount }
    }

    private fun canonical(word: String): String = word.lowercase()

    private fun surfacePreference(canonical: String, surface: String): Int = when {
        surface == canonical -> 0
        surface.drop(1) == canonical.drop(1) -> 1
        else -> 2
    }

    private fun tokenize(text: String): List<String> = WORD_REGEX.findAll(text).map { it.value }.toList()

    suspend fun predict(context: String, maxWords: Int, maxLetters: Int): PredictionResult =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (!trained) return@withContext PredictionResult()
                val parsed = predictionContext(context)
                val rankedWords = if (maxWords > 0 || maxLetters > 0) rankWords(parsed) else emptyList()
                PredictionResult(
                    words = rankedWords.take(maxWords.coerceAtLeast(0))
                        .map { displayWord(it.word, parsed.surfacePrefix) },
                    letters = predictLetters(parsed, rankedWords, maxLetters.coerceAtLeast(0))
                )
            }
        }

    private data class PredictionContext(
        val completedWords: List<String>,
        val prefix: String,
        val surfacePrefix: String
    )

    private fun predictionContext(text: String): PredictionContext {
        // Only the current sentence is relevant. Punctuation such as a comma is
        // intentionally retained as ordinary in-sentence separation.
        val sentence = text.substringAfterLastBoundary()
        val matchAtEnd = WORD_REGEX.findAll(sentence).lastOrNull()
            ?.takeIf { it.range.last == sentence.lastIndex }
        val surfacePrefix = matchAtEnd?.value.orEmpty()
        val allWords = tokenize(sentence).map(::canonical)
        val completed = if (surfacePrefix.isNotEmpty()) allWords.dropLast(1) else allWords
        return PredictionContext(completed, canonical(surfacePrefix), surfacePrefix)
    }

    private fun String.substringAfterLastBoundary(): String {
        val boundary = SENTENCE_BOUNDARY_REGEX.findAll(this).lastOrNull()
        return if (boundary == null) this else substring(boundary.range.last + 1)
    }

    private fun rankWords(parsed: PredictionContext): List<RankedWord> {
        val candidateWords = linkedSetOf<String>()

        val trigram = parsed.completedWords.takeLast(2).takeIf { it.size == 2 }
            ?.joinToString(" ")?.let(trigramCounts::get).orEmpty()
        val bigram = parsed.completedWords.lastOrNull()?.let(bigramCounts::get).orEmpty()
        candidateWords.addAll(trigram.keys)
        candidateWords.addAll(bigram.keys)

        val prefixWords = when {
            parsed.prefix.isEmpty() -> topFrequentWords.take(FALLBACK_CANDIDATE_LIMIT)
            parsed.prefix.length == 1 -> topWordsByFrequency(
                wordsByPrefix[parsed.prefix].orEmpty(), SHORT_PREFIX_CANDIDATE_LIMIT
            )
            else -> topWordsByFrequency(wordsByPrefix[parsed.prefix].orEmpty(), PREFIX_CANDIDATE_LIMIT)
        }
        candidateWords.addAll(prefixWords)

        val filtered = candidateWords.filter { word ->
            parsed.prefix.isEmpty() || (word.startsWith(parsed.prefix) && word != parsed.prefix)
        }
        if (filtered.isEmpty()) return emptyList()

        val trigramTotal = trigram.values.sum().coerceAtLeast(1).toDouble()
        val bigramTotal = bigram.values.sum().coerceAtLeast(1).toDouble()
        // Keep unigram scores on a probability scale so aggregating candidates
        // into letters cannot multiply the fallback weight by vocabulary size.
        val unigramTotal = filtered.sumOf { (wordFrequency[it] ?: 0).toDouble() }.coerceAtLeast(1.0)

        return filtered.asSequence()
            .map { word ->
                val score = TRIGRAM_WEIGHT * ((trigram[word] ?: 0) / trigramTotal) +
                    BIGRAM_WEIGHT * ((bigram[word] ?: 0) / bigramTotal) +
                    UNIGRAM_WEIGHT * ((wordFrequency[word] ?: 0) / unigramTotal)
                RankedWord(word, score, wordFrequency[word] ?: 0)
            }
            .sortedWith(
                compareByDescending<RankedWord> { it.score }
                    .thenByDescending { it.frequency }
                    .thenBy { it.word }
            )
            .toList()
    }

    private data class RankedWord(val word: String, val score: Double, val frequency: Int)

    private fun displayWord(canonical: String, typedPrefix: String): String {
        val learned = preferredSurface[canonical] ?: canonical
        return if (typedPrefix.firstOrNull()?.isUpperCase() == true && learned.firstOrNull()?.isLowerCase() == true) {
            learned.replaceFirstChar { it.uppercase() }
        } else {
            learned
        }
    }

    private fun topWordsByFrequency(words: Collection<String>, limit: Int): List<String> = words
        .asSequence()
        .filter { it.length > 1 }
        .sortedWith(compareByDescending<String> { wordFrequency[it] ?: 0 }.thenBy { it })
        .take(limit)
        .toList()

    private fun predictLetters(
        parsed: PredictionContext,
        rankedWords: List<RankedWord>,
        maxLetters: Int
    ): List<Char> {
        if (maxLetters == 0) return emptyList()
        val prefix = parsed.prefix

        // Both suggestion rows use the same contextual scores. Sum the probability
        // mass of completions sharing a next letter, before limiting either row.
        val completionScores = mutableMapOf<Char, Double>()
        rankedWords.forEach { ranked ->
            val next = ranked.word.getOrNull(prefix.length) ?: return@forEach
            completionScores[next] = (completionScores[next] ?: 0.0) + ranked.score
        }
        if (completionScores.isNotEmpty()) {
            return completionScores.entries
                .sortedWith(compareByDescending<Map.Entry<Char, Double>> { it.value }.thenBy { it.key })
                .take(maxLetters)
                .map { it.key }
        }
        if (prefix.isEmpty()) return emptyList()

        // Keep useful suggestions for unfamiliar words with no known completion.
        val trigram = prefix.takeLast(2).takeIf { it.length == 2 }
            ?.let(letterTrigramCounts::get).orEmpty()
        val bigram = letterBigramCounts[prefix.last()].orEmpty()
        val candidates = trigram.keys + bigram.keys
        val trigramTotal = trigram.values.sum().coerceAtLeast(1).toDouble()
        val bigramTotal = bigram.values.sum().coerceAtLeast(1).toDouble()

        return candidates.asSequence()
            .distinct()
            .map { char ->
                val score = 0.8 * ((trigram[char] ?: 0) / trigramTotal) +
                    0.2 * ((bigram[char] ?: 0) / bigramTotal)
                char to score
            }
            .sortedWith(compareByDescending<Pair<Char, Double>> { it.second }.thenBy { it.first })
            .take(maxLetters)
            .map { it.first }
            .toList()
    }

}
