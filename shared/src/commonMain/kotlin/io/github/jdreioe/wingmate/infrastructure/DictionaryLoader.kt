package io.github.jdreioe.wingmate.infrastructure

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads language dictionaries from the AOSP dictionaries repository on Codeberg.
 * These are used to pretrain the N-Gram model with common words for a language.
 */
class DictionaryLoader {
    private val httpClient by lazy {
        HttpClient {
            followRedirects = true
        }
    }
    
    private val cache = mutableMapOf<String, List<Pair<String, Int>>>()
    
    companion object {
        private const val BASE_URL = "https://codeberg.org/Helium314/aosp-dictionaries/raw/branch/main/wordlists"
        private const val MAX_WORDS = 5000 // Limit to top N words by frequency
    }
    
    /**
     * Fetches and parses the dictionary for a language.
     * Returns a list of (word, frequency) pairs sorted by frequency descending.
     */
    suspend fun loadDictionary(languageCode: String): List<Pair<String, Int>> {
        val langCode = extractLanguageCode(languageCode)
        
        // Return from cache if available
        cache[langCode]?.let { 
            println("DEBUG: Dictionary for $langCode found in cache")
            return it 
        }
        
        // Use NonCancellable to ensure loading finishes even if UI recomposes
        return withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            val url = "$BASE_URL/main_$langCode.combined"
            
            println("DEBUG: Fetching dictionary from $url")
            
            try {
                val response = httpClient.get(url)
                val responseText = response.bodyAsText()
                
                // Parse on Default dispatcher (CPU bound)
                val words = withContext(Dispatchers.Default) {
                    parseDictionary(responseText)
                }
                
                println("DEBUG: Loaded ${words.size} words for language $langCode")
                
                // Cache the result
                if (words.isNotEmpty()) {
                    cache[langCode] = words
                }
                words
            } catch (e: Exception) {
                println("DEBUG: Failed to fetch dictionary for $langCode: ${e.message}")
                e.printStackTrace()
                emptyList()
            }
        }
    }
    
    private fun parseDictionary(content: String): List<Pair<String, Int>> {
        val words = mutableListOf<Pair<String, Int>>()
        
        content.lineSequence().forEach { line ->
            val trimmed = line.trim()
            if (!trimmed.startsWith("word=")) return@forEach
            
            try {
                // Format: word=i,f=215,flags=,originalFreq=215
                val parts = trimmed.split(",")
                val wordPart = parts.firstOrNull { it.startsWith("word=") }
                val freqPart = parts.firstOrNull { it.startsWith("f=") }
                
                if (wordPart != null && freqPart != null) {
                    val word = wordPart.substringAfter("word=")
                    val frequency = freqPart.substringAfter("f=").toIntOrNull() ?: 0
                    
                    if (word.isNotBlank()) {
                        words.add(word to frequency)
                    }
                }
            } catch (_: Exception) {}
        }
        
        return words
            .sortedByDescending { it.second }
            .take(MAX_WORDS)
    }
    
    /**
     * Extracts the 2-letter language code from a locale string.
     * e.g., "da-DK" -> "da", "en-US" -> "en"
     */
    private fun extractLanguageCode(locale: String): String {
        return locale.split("-", "_").first().lowercase()
    }
    
    /**
     * Checks if a dictionary is available for the given language.
     */
    fun isLanguageSupported(languageCode: String): Boolean {
        val code = extractLanguageCode(languageCode)
        return code in SUPPORTED_LANGUAGES
    }
    
    private val SUPPORTED_LANGUAGES = setOf(
        "da", "de", "en", "es", "fr", "it", "nl", "no", "pl", "pt", "ru", "sv"
    )
}
