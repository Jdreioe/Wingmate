package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.PredictionResult
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.TextPredictionService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A lightweight n-gram based text prediction service.
 * Uses bigrams and trigrams trained on the user's speech history.
 * This is intentionally simple and runs locally without requiring external LLM APIs.
 */
class SimpleNGramPredictionService : TextPredictionService {
    private val log = LoggerFactory.getLogger("SimpleNGramPredictionService")
    
    // Word n-grams: maps context (previous words) to frequency of next word
    private val bigramCounts = ConcurrentHashMap<String, MutableMap<String, Int>>()
    private val trigramCounts = ConcurrentHashMap<String, MutableMap<String, Int>>()
    
    // Letter n-grams for partial word completion
    private val letterBigramCounts = ConcurrentHashMap<Char, MutableMap<Char, Int>>()
    private val letterTrigramCounts = ConcurrentHashMap<String, MutableMap<Char, Int>>()
    
    // Word prefix index for fast partial match
    private val wordsByPrefix = ConcurrentHashMap<String, MutableSet<String>>()
    
    // Word frequency for unigram fallback
    private val wordFrequency = ConcurrentHashMap<String, Int>()
    
    private val trained = AtomicBoolean(false)
    
    override suspend fun train(history: List<SaidText>) = withContext(Dispatchers.Default) {
        log.info("Training prediction model on {} history entries", history.size)
        
        // Clear existing data
        bigramCounts.clear()
        trigramCounts.clear()
        letterBigramCounts.clear()
        letterTrigramCounts.clear()
        wordsByPrefix.clear()
        wordFrequency.clear()
        
        // Process each history entry
        history.forEach { entry ->
            val text = entry.saidText ?: return@forEach
            trainOnText(text)
        }
        
        trained.set(true)
        log.info("Training complete. Vocabulary size: {} words, {} bigrams, {} trigrams",
            wordFrequency.size, bigramCounts.size, trigramCounts.size)
    }
    
    private fun trainOnText(text: String) {
        val words = tokenize(text)
        if (words.isEmpty()) return
        
        // Build word frequency and prefix index
        words.forEach { word ->
            wordFrequency.merge(word, 1) { old, new -> old + new }
            
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
                .merge(nextWord, 1) { old, new -> old + new }
        }
        
        // Build word trigrams (two previous words -> next word)
        for (i in 0 until words.size - 2) {
            val context = "${words[i].lowercase()} ${words[i + 1].lowercase()}"
            val nextWord = words[i + 2]
            trigramCounts.getOrPut(context) { mutableMapOf() }
                .merge(nextWord, 1) { old, new -> old + new }
        }
    }
    
    private fun trainLetterNGrams(word: String) {
        if (word.length < 2) return
        
        // Letter bigrams
        for (i in 0 until word.length - 1) {
            val c1 = word[i].lowercaseChar()
            val c2 = word[i + 1]
            letterBigramCounts.getOrPut(c1) { mutableMapOf() }
                .merge(c2, 1) { old, new -> old + new }
        }
        
        // Letter trigrams
        for (i in 0 until word.length - 2) {
            val context = word.substring(i, i + 2).lowercase()
            val nextChar = word[i + 2]
            letterTrigramCounts.getOrPut(context) { mutableMapOf() }
                .merge(nextChar, 1) { old, new -> old + new }
        }
    }
    
    private fun tokenize(text: String): List<String> {
        // Split on whitespace and punctuation, keeping words only
        return text.split(Regex("[\\s,.!?;:\"'()\\[\\]{}]+"))
            .filter { it.isNotBlank() && it.all { c -> c.isLetter() || c == '-' || c == '\'' } }
    }
    
    override suspend fun predict(context: String, maxWords: Int, maxLetters: Int): PredictionResult = 
        withContext(Dispatchers.Default) {
            if (!trained.get()) {
                log.debug("Prediction skipped: model not trained yet")
                return@withContext PredictionResult()
            }
            
            val words = predictWords(context, maxWords)
            val letters = predictLetters(context, maxLetters)
            
            log.debug("Prediction for '{}': {} words, {} letters", 
                context.takeLast(20), words.size, letters.size)
            
            PredictionResult(words = words, letters = letters)
        }
    
    private fun predictWords(context: String, maxWords: Int): List<String> {
        val trimmed = context.trimEnd()
        val words = tokenize(trimmed)
        val lastWord = context.substringAfterLast(' ', "").takeIf { 
            !context.endsWith(' ') && it.isNotBlank() 
        }
        
        // Case 1: User is typing a partial word - suggest completions
        if (lastWord != null && lastWord.isNotBlank()) {
            val prefix = lastWord.lowercase()
            val completions = wordsByPrefix[prefix]
                ?.filter { it.lowercase() != prefix }
                ?.map { it to (wordFrequency[it] ?: 0) }
                ?.sortedByDescending { it.second }
                ?.take(maxWords)
                ?.map { it.first }
                ?: emptyList()
            
            if (completions.isNotEmpty()) {
                return completions
            }
        }
        
        // Case 2: Predict next word based on n-gram context
        val predictions = mutableListOf<Pair<String, Int>>()
        
        // Try trigram first (higher accuracy)
        if (words.size >= 2) {
            val trigramContext = "${words[words.size - 2].lowercase()} ${words.last().lowercase()}"
            trigramCounts[trigramContext]?.forEach { (word, count) ->
                predictions.add(word to count * 3) // Weight trigrams higher
            }
        }
        
        // Add bigram predictions
        if (words.isNotEmpty()) {
            val bigramContext = words.last().lowercase()
            bigramCounts[bigramContext]?.forEach { (word, count) ->
                predictions.add(word to count * 2)
            }
        }
        
        // Fallback to most frequent words
        if (predictions.isEmpty()) {
            wordFrequency.entries
                .sortedByDescending { it.value }
                .take(maxWords)
                .forEach { predictions.add(it.key to it.value) }
        }
        
        // Merge and sort by weighted count
        return predictions
            .groupBy { it.first }
            .map { (word, counts) -> word to counts.sumOf { it.second } }
            .sortedByDescending { it.second }
            .take(maxWords)
            .map { it.first }
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
    
    override fun isTrained(): Boolean = trained.get()
}
