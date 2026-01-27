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
class DictionaryLoader(private val fileStorage: io.github.jdreioe.wingmate.domain.FileStorage? = null) {
    private val httpClient by lazy {
        HttpClient {
            followRedirects = true
        }
    }
    
    private val cache = mutableMapOf<String, List<Pair<String, Int>>>()
    
    companion object {
        private const val BASE_URL = "https://codeberg.org/Helium314/aosp-dictionaries/raw/branch/main/wordlists"
        private const val MAX_WORDS = 10000 // Limit to top N words by frequency
    }
    
    /**
     * Fetches and parses the dictionary for a language.
     * Returns a list of (word, frequency) pairs sorted by frequency descending.
     */
    suspend fun loadDictionary(languageCode: String): List<Pair<String, Int>> {
        val baseName = resolveDictionaryBaseName(languageCode) ?: return emptyList()
        val cacheKey = baseName
        
        // Return from memory cache if available
        cache[cacheKey]?.let { 
            println("DEBUG: Dictionary for $languageCode (file=$baseName) found in memory cache")
            return it 
        }
        
        // Use NonCancellable to ensure loading finishes even if UI recomposes
        return withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
            // Check disk cache first
            if (fileStorage != null) {
                val fileName = "$baseName.combined"
                val cachedContent = fileStorage.load(fileName)
                if (cachedContent != null && cachedContent.isNotBlank()) {
                    println("DEBUG: Dictionary for $languageCode loaded from disk cache ($fileName)")
                    val words = withContext(Dispatchers.Default) {
                        parseDictionary(cachedContent)
                    }
                    if (words.isNotEmpty()) {
                        cache[cacheKey] = words
                        return@withContext words
                    }
                }
            }
            
            val url = "$BASE_URL/$baseName.combined"
            
            println("DEBUG: Fetching dictionary from $url")
            
            try {
                val response = httpClient.get(url)
                val responseText = response.bodyAsText()
                
                // Parse on Default dispatcher (CPU bound)
                val words = withContext(Dispatchers.Default) {
                    parseDictionary(responseText)
                }
                
                println("DEBUG: Loaded ${words.size} words for language $languageCode ($baseName)")
                
                // Cache the result
                if (words.isNotEmpty()) {
                    cache[cacheKey] = words
                    
                    // Save to disk cache
                    if (fileStorage != null) {
                        val fileName = "$baseName.combined"
                        fileStorage.save(fileName, responseText)
                        println("DEBUG: Saved dictionary for $languageCode to disk as $fileName")
                    }
                }
                words
            } catch (e: Exception) {
                println("DEBUG: Failed to fetch dictionary for $languageCode ($url): ${e.message}")
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
     * Resolves the dictionary filename base (without extension) for a given locale.
     * e.g. "en-US" -> "main_en_US", "de-CH" -> "main_de_CH"
     */
    private fun resolveDictionaryBaseName(locale: String): String? {
        val parts = locale.replace("-", "_").split("_")
        val lang = parts.firstOrNull()?.lowercase() ?: return null
        val region = parts.getOrNull(1)?.uppercase() // AOSP dicts use uppercase region usually (e.g. en_US)
        
        // 1. Try exact match with uppercase region (e.g. main_en_US)
        if (region != null) {
             val candidate = "main_${lang}_${region}"
             if (SUPPORTED_DICTIONARIES.contains(candidate)) return candidate
        }
        
        // 2. Try exact match with original casing provided or just lowercase as fallback check
        // (Use the clean locale from before if needed, but we rely on the specific list keys now)
        
        // 3. Fallback to base language (e.g. main_de)
        val candidateBase = "main_$lang"
        if (SUPPORTED_DICTIONARIES.contains(candidateBase)) return candidateBase

        // 4. Specific Defaults
        return when (lang) {
            "en" -> "main_en_US"
            "pt" -> "main_pt_BR"
            else -> null
        }
    }

    // Set of known available dictionaries in the repo
    private val SUPPORTED_DICTIONARIES = setOf(
        "main_ar", "main_as", "main_be", "main_bg", "main_bn", "main_bs", 
        "main_ca", "main_cs", "main_da", "main_de", "main_de_CH", "main_el", 
        "main_en_AU", "main_en_GB", "main_en_US", "main_eo", "main_es", "main_eu", 
        "main_fi", "main_fr", "main_gl", "main_gom", "main_gu", "main_he", "main_hi", 
        "main_hi_ZZ", "main_hr", "main_hu", "main_hy", "main_it", "main_iw", "main_ka", 
        "main_km", "main_kn", "main_ks", "main_la", "main_lb", "main_lt", "main_lv", 
        "main_mai", "main_mk", "main_ml", "main_mr", "main_mwl", "main_nb", "main_nl", 
        "main_or", "main_pa", "main_pl", "main_pt_BR", "main_pt_PT", "main_ro", 
        "main_ru", "main_sa", "main_sat", "main_sd", "main_sl", "main_sr", "main_sr_ZZ", 
        "main_sv", "main_ta", "main_tcy", "main_te", "main_tok", "main_tr", "main_uk", 
        "main_ur", "main_zgh", "main_zgh_ZZ"
    )
    
    /**
     * Checks if a dictionary is available for the given language.
     */
    fun isLanguageSupported(languageCode: String): Boolean {
        return resolveDictionaryBaseName(languageCode) != null
    }
}
