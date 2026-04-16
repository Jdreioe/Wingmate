package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PredictionResult
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.TextPredictionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A lightweight n-gram based text prediction service.
 * Uses bigrams and trigrams trained on the user's speech history.
 * This is intentionally simple and runs locally without requiring external LLM APIs.
 */
class SimpleNGramPredictionService : TextPredictionService {
    private val log = KotlinLogging.logger("SimpleNGramPredictionService")
    private val mutex = Mutex()

    private companion object {
        private const val SHORT_PREFIX_LENGTH = 1
        private const val SHORT_PREFIX_CANDIDATE_LIMIT = 32
        private const val PREFIX_CANDIDATE_LIMIT = 96
        private const val UNIGRAM_FALLBACK_LIMIT = 96
        private const val TOP_WORD_CACHE_LIMIT = 512
        private val TOKEN_SPLIT_REGEX = Regex("[\\s,.!?;:]+")
    }
    
    // Word n-grams: maps context (previous words) to frequency of next word
    private val bigramCounts = mutableMapOf<String, MutableMap<String, Int>>()
    private val trigramCounts = mutableMapOf<String, MutableMap<String, Int>>()
    
    // Letter n-grams for partial word completion
    private val letterBigramCounts = mutableMapOf<Char, MutableMap<Char, Int>>()
    private val letterTrigramCounts = mutableMapOf<String, MutableMap<Char, Int>>()
    
    // Word prefix index for fast partial match
    private val wordsByPrefix = mutableMapOf<String, MutableSet<String>>()
    
    // Word frequency for unigram fallback
    private val wordFrequency = mutableMapOf<String, Int>()

    // Cached top words for low-cost fallback and short-prefix suggestions
    private var topFrequentWords: List<String> = emptyList()
    
    private var trained = false
    
    /**
     * Train the model on a list of phrases.
     * @param history List of phrases to train on
     * @param clear If true, wipes existing model data before training. Set false to append to existing data.
     */
    override suspend fun train(history: List<SaidText>) = train(history, true)

    suspend fun train(history: List<SaidText>, clear: Boolean) = withContext(Dispatchers.Default) {
        mutex.withLock {
            if (clear) {
                // Clear existing data
                bigramCounts.clear()
                trigramCounts.clear()
                letterBigramCounts.clear()
                letterTrigramCounts.clear()
                wordsByPrefix.clear()
                wordFrequency.clear()
            }
            
            // Process each history entry
            history.forEach { entry ->
                val text = entry.saidText ?: return@forEach
                trainOnText(text)
            }

            rebuildTopFrequentWords()
            
            trained = true
            log.debug {
                "Training complete. vocab=${wordFrequency.size}, prefixes=${wordsByPrefix.size}, bigrams=${bigramCounts.size}, trigrams=${trigramCounts.size}"
            }
        }
    }
    
    /** Incrementally add a single phrase to the model without full retrain */
    suspend fun learnPhrase(text: String) = withContext(Dispatchers.Default) {
        mutex.withLock {
            trainOnText(text)
            rebuildTopFrequentWords()
            trained = true
        }
    }
    
    /** 
     * Set the base language for dictionary pretraining.
     * This loads common words for the language and adds them to the model.
     * User's own words will be added on top with higher priority.
     */
    suspend fun setBaseLanguage(words: List<Pair<String, Int>>) = withContext(Dispatchers.Default) {
        mutex.withLock {
            // Clear existing linguistic data before switching languages
            bigramCounts.clear()
            trigramCounts.clear()
            letterBigramCounts.clear()
            letterTrigramCounts.clear()
            wordsByPrefix.clear()
            wordFrequency.clear()

            // Add dictionary words with their frequency weights
            words.forEach { (word, frequency) ->
                // Use lower weight for dictionary words so user words have priority
                // Frequency is roughly 0-255. Divide by 10 gives range 0-25.
                // User words increase by 1 per usage.
                val weight = (frequency / 10).coerceAtLeast(1)
                wordFrequency[word] = (wordFrequency[word] ?: 0) + weight
                
                // Index by all prefixes
                for (len in 1..word.length) {
                    val prefix = word.substring(0, len).lowercase()
                    wordsByPrefix.getOrPut(prefix) { mutableSetOf() }.add(word)
                }
                
                // Train letter n-grams with weight
                trainLetterNGrams(word, weight)
            }

            rebuildTopFrequentWords()
            
            trained = true
            log.debug {
                "Base language loaded. vocab=${wordFrequency.size}, prefixes=${wordsByPrefix.size}"
            }
        }
    }

    private fun rebuildTopFrequentWords() {
        topFrequentWords = wordFrequency.entries
            .asSequence()
            .sortedByDescending { it.value }
            .map { it.key }
            .take(TOP_WORD_CACHE_LIMIT)
            .toList()
    }
    
    private fun trainOnText(text: String) {
        val words = tokenize(text)
        if (words.isEmpty()) return
        
        // Build word frequency and prefix index
        words.forEach { word ->
            wordFrequency[word] = (wordFrequency[word] ?: 0) + 1
            
            // Index by all prefixes
            for (len in 1..word.length) {
                val prefix = word.substring(0, len).lowercase()
                wordsByPrefix.getOrPut(prefix) { mutableSetOf() }.add(word)
            }
            
            // Train letter n-grams within words
            trainLetterNGrams(word)
        }
        
        // Build word bigrams (previous word -> next word)
        for (i in 0 until words.size - 1) {
            val context = words[i].lowercase()
            val nextWord = words[i + 1]
            bigramCounts.getOrPut(context) { mutableMapOf() }
                .let { it[nextWord] = (it[nextWord] ?: 0) + 1 }
        }
        
        // Build word trigrams (two previous words -> next word)
        for (i in 0 until words.size - 2) {
            val context = "${words[i].lowercase()} ${words[i + 1].lowercase()}"
            val nextWord = words[i + 2]
            trigramCounts.getOrPut(context) { mutableMapOf() }
                .let { it[nextWord] = (it[nextWord] ?: 0) + 1 }
        }
    }
    
    private fun trainLetterNGrams(word: String, weight: Int = 1) {
        if (word.length < 2) return
        
        // Letter bigrams
        for (i in 0 until word.length - 1) {
            val c1 = word[i].lowercaseChar()
            val c2 = word[i + 1]
            letterBigramCounts.getOrPut(c1) { mutableMapOf() }
                .let { it[c2] = (it[c2] ?: 0) + weight }
        }
        
        // Letter trigrams
        for (i in 0 until word.length - 2) {
            val context = word.substring(i, i + 2).lowercase()
            val nextChar = word[i + 2]
            letterTrigramCounts.getOrPut(context) { mutableMapOf() }
                .let { it[nextChar] = (it[nextChar] ?: 0) + weight }
        }
    }
    
    private fun tokenize(text: String): List<String> {
        // Split on whitespace and common punctuation only
        // Keep words with letters, hyphens, and apostrophes (for contractions)
        return text.split(TOKEN_SPLIT_REGEX)
            .map { it.trim('"', '\'', '(', ')', '[', ']', '{', '}') }
            .filter { it.isNotBlank() && it.any { c -> c.isLetter() } }
    }
    
    override suspend fun predict(context: String, maxWords: Int, maxLetters: Int): PredictionResult = 
        withContext(Dispatchers.Default) {
            mutex.withLock {
                if (!trained) {
                    log.debug { "Prediction skipped: model not trained yet" }
                    return@withContext PredictionResult()
                }
                
                val words = predictWords(context, maxWords)
                val letters = predictLetters(context, maxLetters)
                
                log.debug { "Prediction for '${context.takeLast(20)}': ${words.size} words, ${letters.size} letters" }
                
                PredictionResult(words = words, letters = letters)
            }
        }
    
    private fun predictWords(context: String, maxWords: Int): List<String> {
        if (maxWords <= 0) return emptyList()

        val trimmed = context.trimEnd()
        val historyWordsForContext = tokenize(trimmed)
        
        // Check if user is currently typing a word (no trailing space)
        val lastWordInProgress = if (context.isNotEmpty() && !context.endsWith(' ')) {
            context.substringAfterLast(' ', context).takeIf { it.isNotBlank() }
        } else null
        
        val prefix = lastWordInProgress?.lowercase() ?: ""
        
        // Collect all candidates with weights
        val candidates = mutableMapOf<String, Double>()
        
        // 1. Trigram signals (highest precision)
        // If we are typing a word, context is the two words BEFORE it.
        // If we just typed a space, context is the two words BEFORE the space.
        val contextForNGrams = if (lastWordInProgress != null) {
            historyWordsForContext.dropLast(1)
        } else {
            historyWordsForContext
        }

        if (contextForNGrams.size >= 2) {
            val trigramKey = "${contextForNGrams[contextForNGrams.size - 2].lowercase()} ${contextForNGrams.last().lowercase()}"
            trigramCounts[trigramKey]?.forEach { (word, count) ->
                candidates[word] = (candidates[word] ?: 0.0) + (count * 10.0)
            }
        }
        
        // 2. Bigram signals
        if (contextForNGrams.isNotEmpty()) {
            val bigramKey = contextForNGrams.last().lowercase()
            bigramCounts[bigramKey]?.forEach { (word, count) ->
                candidates[word] = (candidates[word] ?: 0.0) + (count * 5.0)
            }
        }
        
        // 3. Unigram / Completion signals
        if (prefix.isNotEmpty()) {
            val prefixCandidates = if (prefix.length == SHORT_PREFIX_LENGTH) {
                // For one-letter prefixes, use a small top-word cache to avoid scanning huge sets on every keypress.
                topFrequentWords.asSequence()
                    .filter { it.startsWith(prefix, ignoreCase = true) && !it.equals(prefix, ignoreCase = true) }
                    .take(SHORT_PREFIX_CANDIDATE_LIMIT)
                    .toList()
            } else {
                topWordsByFrequency(
                    words = wordsByPrefix[prefix] ?: emptySet(),
                    limit = PREFIX_CANDIDATE_LIMIT,
                    requiredPrefix = prefix
                )
            }

            prefixCandidates.forEach { word ->
                candidates[word] = (candidates[word] ?: 0.0) + (wordFrequency[word] ?: 1).toDouble()
            }
        } else {
            // Fallback to top-frequency words only, to keep predictions cheap when there's no active prefix.
            topFrequentWords.take(UNIGRAM_FALLBACK_LIMIT).forEach { word ->
                val count = wordFrequency[word] ?: 1
                candidates[word] = (candidates[word] ?: 0.0) + (count * 0.1)
            }
        }

        // Final filtering and ranking
        val result = candidates.entries
            .asSequence()
            .filter { (word, _) ->
                // If user is typing, only show words starting with that prefix (completions)
                // If user just typed a space, show any likely next words
                if (prefix.isNotEmpty()) {
                    word.lowercase().startsWith(prefix) && word.lowercase() != prefix
                } else {
                    true
                }
            }
            .sortedByDescending { it.value }
            .map { it.key }
            .distinct()
            .take(maxWords)
            .toList()
        return result
    }

    private fun topWordsByFrequency(
        words: Collection<String>,
        limit: Int,
        requiredPrefix: String? = null
    ): List<String> {
        if (limit <= 0 || words.isEmpty()) return emptyList()

        val selectedWords = ArrayList<String>(limit)
        val selectedScores = ArrayList<Int>(limit)

        words.forEach { word ->
            if (requiredPrefix != null) {
                if (!word.startsWith(requiredPrefix, ignoreCase = true) || word.equals(requiredPrefix, ignoreCase = true)) {
                    return@forEach
                }
            }

            val score = wordFrequency[word] ?: 0
            if (selectedScores.size == limit && score <= selectedScores.last()) return@forEach

            var index = 0
            while (index < selectedScores.size && score <= selectedScores[index]) {
                index++
            }
            if (index >= limit) return@forEach

            selectedWords.add(index, word)
            selectedScores.add(index, score)

            if (selectedWords.size > limit) {
                selectedWords.removeAt(limit)
                selectedScores.removeAt(limit)
            }
        }

        return selectedWords
    }
    
    private fun predictLetters(context: String, maxLetters: Int): List<Char> {
        if (context.isBlank()) return emptyList()
        
        val lastWord = context.substringAfterLast(' ', context)
        if (lastWord.isBlank()) return emptyList()
        
        val predictions = mutableListOf<Pair<Char, Int>>()
        
        // Try letter trigram
        if (lastWord.length >= 2) {
            val trigramContext = lastWord.takeLast(2).lowercase()
            letterTrigramCounts[trigramContext]?.forEach { (char, count) ->
                predictions.add(char to count * 2)
            }
        }
        
        // Add letter bigram predictions
        if (lastWord.isNotEmpty()) {
            val lastChar = lastWord.last().lowercaseChar()
            letterBigramCounts[lastChar]?.forEach { (char, count) ->
                predictions.add(char to count)
            }
        }
        
        // Return most likely letters
        return predictions
            .groupBy { it.first }
            .map { (char, counts) -> char to counts.sumOf { it.second } }
            .sortedByDescending { it.second }
            .take(maxLetters)
            .map { it.first }
    }
    
    override fun isTrained(): Boolean = trained
}
