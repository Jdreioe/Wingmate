package io.github.jdreioe.wingmate.domain

import kotlinx.serialization.Serializable

/**
 * Data class representing a segment of speech text with optional pause
 */
@Serializable
data class SpeechSegment(
    val text: String,
    val pauseDurationMs: Long = 0,
    val languageTag: String? = null // Optional language override for this segment
)

/**
 * Processes text to extract pause commands and split into segments
 */
object SpeechTextProcessor {
    
    /**
     * Processes text containing XML pause tags and returns segments
     * Supported tags:
     * - <pause duration="1000"/> - Pause for 1000ms
     * - <pause duration="1s"/> - Pause for 1 second  
     * - <pause/> - Default pause of 500ms
     * - <break time="2s"/> - SSML-style break (alias for pause)
     */
    fun processText(text: String): List<SpeechSegment> {
        // First, merge lines that don't end with proper punctuation
        val mergedText = mergeIncompleteLines(text)
        
        val segments = mutableListOf<SpeechSegment>()
        
        // Regular expression to match pause tags
        val pauseRegex = Regex("""<(?:pause|break)(?:\s+(?:duration|time)=["']([^"']+)["'])?[^>]*/>""", RegexOption.IGNORE_CASE)
        
        var currentIndex = 0
        
        pauseRegex.findAll(mergedText).forEach { match ->
            // Add text segment before the pause tag
            val textBeforePause = mergedText.substring(currentIndex, match.range.first).trim()
            if (textBeforePause.isNotEmpty()) {
                val pauseDuration = parseDuration(match.groupValues.getOrNull(1))
                segments.add(SpeechSegment(textBeforePause, pauseDuration))
            } else if (segments.isNotEmpty()) {
                // If there's no text before the pause, add the pause to the last segment
                val lastSegment = segments.removeLastOrNull()
                if (lastSegment != null) {
                    val pauseDuration = parseDuration(match.groupValues.getOrNull(1))
                    segments.add(lastSegment.copy(pauseDurationMs = lastSegment.pauseDurationMs + pauseDuration))
                }
            }
            
            currentIndex = match.range.last + 1
        }
        
        // Add remaining text after the last pause tag
        val remainingText = mergedText.substring(currentIndex).trim()
        if (remainingText.isNotEmpty()) {
            segments.add(SpeechSegment(remainingText))
        }
        
        // If no pause tags were found, return the entire text as a single segment
        if (segments.isEmpty() && mergedText.trim().isNotEmpty()) {
            segments.add(SpeechSegment(mergedText.trim()))
        }
        
        return segments
    }
    
    /**
     * Parses duration string to milliseconds
     * Supports formats: "1000", "1000ms", "1s", "1.5s", "2.0"
     */
    private fun parseDuration(durationStr: String?): Long {
        if (durationStr.isNullOrBlank()) return 500L // Default pause duration
        
        val cleanDuration = durationStr.trim().lowercase()
        
        return when {
            cleanDuration.endsWith("ms") -> {
                cleanDuration.removeSuffix("ms").toDoubleOrNull()?.toLong() ?: 500L
            }
            cleanDuration.endsWith("s") -> {
                val seconds = cleanDuration.removeSuffix("s").toDoubleOrNull() ?: 0.5
                (seconds * 1000).toLong()
            }
            else -> {
                // Assume milliseconds if no unit specified
                cleanDuration.toDoubleOrNull()?.toLong() ?: 500L
            }
        }
    }
    
    /**
     * Removes all pause tags from text, returning clean text
     */
    fun stripPauseTags(text: String): String {
        val pauseRegex = Regex("""<(?:pause|break)(?:\s+(?:duration|time)=["'][^"']+["'])?[^>]*/>""", RegexOption.IGNORE_CASE)
        return pauseRegex.replace(text, "").replace(Regex("""\s+"""), " ").trim()
    }
    
    /**
     * Merges lines that don't end with proper sentence-ending punctuation
     * This is especially useful for text copied from PDFs where lines are broken arbitrarily
     * 
     * @param text Input text with potentially broken lines
     * @return Text with incomplete lines merged together
     */
    private fun mergeIncompleteLines(text: String): String {
        val lines = text.split('\n')
        if (lines.size <= 1) return text
        
        val mergedLines = mutableListOf<String>()
        var currentLine = ""
        
        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) {
                // Empty line - finish current line and add empty line
                if (currentLine.isNotEmpty()) {
                    mergedLines.add(currentLine.trim())
                    currentLine = ""
                }
                mergedLines.add("")
                continue
            }
            
            if (currentLine.isEmpty()) {
                currentLine = trimmedLine
            } else {
                // Check if the previous line ends with sentence-ending punctuation
                val lastChar = currentLine.trimEnd().lastOrNull()
                if (lastChar != null && lastChar in setOf('.', '!', '?', ':', ';')) {
                    // Previous line ends with punctuation - start new line
                    mergedLines.add(currentLine.trim())
                    currentLine = trimmedLine
                } else {
                    // Previous line doesn't end with punctuation - merge with current line
                    currentLine = "$currentLine $trimmedLine"
                }
            }
        }
        
        // Add the final line if it exists
        if (currentLine.isNotEmpty()) {
            mergedLines.add(currentLine.trim())
        }
        
        return mergedLines.joinToString("\n")
    }

    /**
     * Get example texts that demonstrate pause tag functionality and PDF line merging
     */
    fun getExampleTexts(): List<String> = listOf(
        "Hello there. <pause duration=\"1s\"/> How are you today?",
        "First sentence. <pause/> Second sentence after default pause. <pause duration=\"2s\"/> Third sentence after 2 second pause.",
        "Welcome to the presentation. <break time=\"1.5s\"/> Let's begin with the first topic. <pause duration=\"500ms\"/> This is important information.",
        "One. <pause duration=\"1s\"/> Two. <pause duration=\"1s\"/> Three. <pause duration=\"1s\"/> Ready!",
        "This is a test of <pause duration=\"2000\"/> a long pause in milliseconds.",
        // Examples showing PDF line merging (simulating copy-paste from PDF)
        "This is a sentence that was\nbroken across multiple lines\nwhen copied from a PDF. <pause/> This sentence ends properly.\nSo this starts a new sentence.",
        "The quick brown fox\njumps over the lazy\ndog. <pause duration=\"1s\"/> This demonstrates how\ntext copied from PDFs\ngets automatically merged.",
        "Lorem ipsum dolor sit\namet, consectetur\nadipiscing elit. <break time=\"2s\"/> Sed do eiusmod\ntempor incididunt ut\nlabore et dolore magna aliqua."
    )

    /**
     * Get examples specifically for testing PDF line merging without pause tags
     */
    fun getPdfMergeExamples(): List<String> = listOf(
        "This sentence was broken\nacross multiple lines when\ncopied from a PDF document.",
        "First complete sentence. Second sentence\nwas unfortunately split\ninto multiple lines.",
        "The benefits of artificial\nintelligence include improved\nefficiency and automation.\nHowever, there are also\nconcerns about job displacement.",
        "Machine learning algorithms\ncan process vast amounts\nof data quickly. They identify\npatterns humans might miss."
    )
}