package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.SpeechServiceConfig
import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import org.koin.core.context.GlobalContext
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import java.io.File
import javazoom.jl.player.Player
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import javax.sound.sampled.*
import java.awt.Desktop
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean

import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.serialization.json.*
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository

class DesktopSpeechService(
    private val dictionaryRepo: PronunciationDictionaryRepository? = null
) : SpeechService {
    private val client = HttpClient(OkHttp) {}
    private val log = LoggerFactory.getLogger("DesktopSpeechService")
    
    // Track current playback state
    private var currentPlayer: Player? = null
    private var currentProcess: Process? = null
    private var isPlaying = false
    private var isPaused = false
    private val stopRequested = AtomicBoolean(false)
    private val virtualSinkName = "wingmate_vmic"
    private val virtualSinkDesc = "Wingmate Virtual Mic"
    
    // Enhanced state for segmented playback and resume
    private var currentSegments: List<SpeechSegment> = emptyList()
    private var currentSegmentIndex = 0
    private var currentVoice: Voice? = null
    private var currentPitch: Double? = null
    private var currentRate: Double? = null
    private var pausedAtSegment = false

    init {
        log.info("Enhanced DesktopSpeechService initialized with Azure TTS and System TTS support")
    }

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        log.info("speak() called with text='{}', voice={}, pitch={}, rate={}", text.take(50), voice?.name, pitch, rate)
        log.info("speak() called on thread={}", Thread.currentThread().name)
        
        var processedText = text
        
        // Apply dictionary replacements if available
        if (dictionaryRepo != null) {
            val entries = dictionaryRepo.getAll()
            if (entries.isNotEmpty()) {
                processedText = applyDictionary(processedText, entries)
            }
        }

        // Check if text contains SSML markup (user inserted via sidebar buttons OR dictionary injection)
        // Look for common SSML tags: <emphasis>, <break>, <phoneme>, <say-as>, <lang>, etc.
        val containsSSML = processedText.contains(Regex("<(emphasis|break|phoneme|say-as|lang|prosody|voice)"))
        
        if (containsSSML) {
            log.info("Detected SSML markup in text; bypassing text processing and using Azure TTS directly")
            
            // Convert <pause> to <break> for Azure compatibility since we are bypassing SpeechTextProcessor
            processedText = processedText.replace(Regex("<pause\\s+duration=[\"']([^\"']+)[\"']\\s*/>")) { 
                "<break time=\"${it.groupValues[1]}\"/>" 
            }.replace("<pause/>", "<break time=\"500ms\"/>")
            
            // Don't process SSML through SpeechTextProcessor - send directly to Azure with voice wrapping
            speakWithAzureTts(processedText, voice, pitch, rate)
            return
        }
        
        // For plain text, process to extract pause tags and create segments
        val segments = SpeechTextProcessor.processText(processedText)
        log.info("Processed text into {} segments", segments.size)
        
        // Use the enhanced speakSegments function
        speakSegments(segments, voice, pitch, rate)
    }

    private fun applyDictionary(text: String, entries: List<io.github.jdreioe.wingmate.domain.PronunciationEntry>): String {
        val tagRegex = "<[^>]+>".toRegex()
        val parts = mutableListOf<String>()
        var lastIndex = 0
        
        tagRegex.findAll(text).forEach { match ->
            // Text before tag
            val textPart = text.substring(lastIndex, match.range.first)
            parts.add(replaceWords(textPart, entries))
            // The tag itself (unchanged)
            parts.add(match.value)
            lastIndex = match.range.last + 1
        }
        // Remaining text
        val textPart = text.substring(lastIndex)
        parts.add(replaceWords(textPart, entries))
        
        return parts.joinToString("")
    }

    private fun replaceWords(text: String, entries: List<io.github.jdreioe.wingmate.domain.PronunciationEntry>): String {
        var processed = text
        for (entry in entries) {
             val pattern = "\\b${Regex.escape(entry.word)}\\b".toRegex(RegexOption.IGNORE_CASE)
             if (pattern.containsMatchIn(processed)) {
                 processed = processed.replace(pattern) { matchResult ->
                     "<phoneme alphabet=\"${entry.alphabet}\" ph=\"${entry.phoneme}\">${matchResult.value}</phoneme>"
                 }
             }
        }
        return processed
    }

    override suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) {
        log.info("speakSegments() called with {} segments, voice={}, pitch={}, rate={}", segments.size, voice?.name, pitch, rate)
        
        // Reset stop flag for this utterance
        stopRequested.set(false)

        // Prevent overlap: if something is currently playing, stop it first
        if (isPlaying || currentPlayer != null || currentProcess != null) {
            log.info("speakSegments() detected existing playback; invoking stop() before starting new utterance")
            try { stop() } catch (t: Throwable) { log.warn("error stopping previous playback", t) }
            // After stop, ensure state reflects idle
            currentPlayer = null
            currentProcess = null
            isPlaying = false
            isPaused = false
        }

        val hasLanguageOverrides = segments.any { !it.languageTag.isNullOrBlank() }
        if (hasLanguageOverrides && trySpeakSegmentsWithAzureSsml(segments, voice, pitch, rate)) {
            log.info("Combined Azure SSML playback succeeded; skipping segmented playback")
            return
        }
        
        // Store current playback context for resume functionality
        currentSegments = segments
        currentSegmentIndex = 0
        currentVoice = voice
        currentPitch = pitch
        currentRate = rate
        pausedAtSegment = false
        
        // Start playing segments
        playSegmentsFromIndex(0)
    }

    private suspend fun maybePlayFromCache(
        text: String,
        voice: Voice?,
        pitch: Double?,
        rate: Double?,
        uiPrimaryLanguage: String?
    ): Boolean {
        return runCatching {
            val koin = GlobalContext.getOrNull()
            val repo = koin?.get<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
            if (repo == null) return false

            // Determine effective voice/lang similar to Azure path
            val baseVoice = voice ?: Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")
            val enhancedVoice = baseVoice.copy(
                pitch = pitch ?: baseVoice.pitch,
                rate = rate ?: baseVoice.rate
            )
            val effectiveLang = when {
                !enhancedVoice.selectedLanguage.isNullOrBlank() -> enhancedVoice.selectedLanguage
                !uiPrimaryLanguage.isNullOrBlank() -> uiPrimaryLanguage
                !enhancedVoice.primaryLanguage.isNullOrBlank() -> enhancedVoice.primaryLanguage
                else -> "en-US"
            }
            val vForMatch = enhancedVoice.copy(primaryLanguage = effectiveLang)

            val list = runCatching { repo.list() }.getOrNull().orEmpty()
            val targetPitch = vForMatch.pitch
            val targetRate = vForMatch.rate

            val candidate = list.asSequence()
                .filter { it.saidText == text }
                .filter { !it.audioFilePath.isNullOrBlank() }
                .filter { it.voiceName == vForMatch.name }
                .filter { (it.primaryLanguage ?: "") == (vForMatch.primaryLanguage ?: "") }
                .filter { (it.pitch == null && targetPitch == null) || (it.pitch != null && targetPitch != null && it.pitch == targetPitch) }
                .filter { (it.speed == null && targetRate == null) || (it.speed != null && targetRate != null && it.speed == targetRate) }
                .sortedByDescending { it.date ?: it.createdAt ?: 0L }
                .firstOrNull()

            if (candidate != null) {
                val path = candidate.audioFilePath!!
                val file = File(path)
                if (file.exists() && file.length() > 0L) {
                    log.info("reusing cached audio from history: {}", file.absolutePath)
                    // Record a new history entry referencing the same audio file for this play
                    val now = System.currentTimeMillis()
                    runCatching {
                        repo.add(
                            io.github.jdreioe.wingmate.domain.SaidText(
                                date = now,
                                saidText = text,
                                voiceName = vForMatch.name,
                                pitch = vForMatch.pitch,
                                speed = vForMatch.rate,
                                audioFilePath = file.absolutePath,
                                createdAt = now,
                                position = 0,
                                primaryLanguage = vForMatch.selectedLanguage?.takeIf { it.isNotBlank() } ?: vForMatch.primaryLanguage
                            )
                        )
                    }.onFailure { t -> log.warn("Failed to append cached-play history", t) }

                    // Play the cached file
                    withContext(Dispatchers.IO) { playAudioFile(file) }
                    return true
                } else {
                    log.info("cached history file missing or empty; will synthesize via API: {}", path)
                }
            }
            false
        }.getOrElse { e ->
            log.warn("cache reuse attempt failed; falling back to API", e)
            false
        }
    }

    private suspend fun speakWithAzureTts(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        val cfg = getConfig() ?: throw IllegalStateException("No Azure TTS configuration found. Please configure Azure endpoint and subscription key.")
        
        // Enhanced voice parameter handling
        val baseVoice = voice ?: Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")
        
        // Apply pitch and rate parameters if provided
        val enhancedVoice = baseVoice.copy(
            pitch = pitch ?: baseVoice.pitch,
            rate = rate ?: baseVoice.rate
        )

        // Resolve effective language for SSML in priority order:
        // 1. voice.selectedLanguage (per-voice selection)
        // 2. persisted UI primary language (SettingsRepository)
        // 3. voice.primaryLanguage
        // 4. fallback "en-US"
        val koin = GlobalContext.getOrNull()
        val settingsRepo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
        val uiSettings = settingsRepo?.let { runCatching { runBlocking { it.get() } }.getOrNull() }
        val effectiveLang = when {
            !enhancedVoice.selectedLanguage.isNullOrBlank() -> enhancedVoice.selectedLanguage
            !uiSettings?.primaryLanguage.isNullOrBlank() -> uiSettings!!.primaryLanguage
            !enhancedVoice.primaryLanguage.isNullOrBlank() -> enhancedVoice.primaryLanguage
            else -> "en-US"
        } ?: "en-US"

        val vForSsml = enhancedVoice.copy(primaryLanguage = effectiveLang)
        
        try {
            // Check if text contains SSML markup (user inserted via sidebar)
            val hasSSML = text.contains("<") && text.contains(">") && !text.trim().startsWith("<speak")
            
            val ssml = if (hasSSML) {
                log.info("Text contains SSML markup; wrapping with voice/language info")
                // Wrap user SSML in proper voice/prosody/language tags
                wrapSSMLWithVoiceInfo(text, vForSsml)
            } else {
                log.info("Generating SSML from plain text")
                // Generate complete SSML from plain text
                AzureTtsClient.generateSsml(text, vForSsml)
            }
            
            log.debug("Final SSML: ${ssml.take(300)}...")
            synthesizeAndPlayAzureSsml(ssml, vForSsml, cfg, text)
        } catch (e: Exception) {
            log.error("Azure TTS synthesis failed", e)
            throw RuntimeException("Azure TTS failed: ${e.message}", e)
        }
    }
    
    /**
     * Wrap user-provided SSML content with proper voice/language/prosody tags.
     * User content like "<emphasis>hello</emphasis>" gets wrapped as:
     * <speak>
     *   <voice name="...">
     *     <prosody pitch="..." rate="...">
     *       <lang xml:lang="...">
     *         <emphasis>hello</emphasis>
     *       </lang>
     *     </prosody>
     *   </voice>
     * </speak>
     */
    private fun wrapSSMLWithVoiceInfo(userSSML: String, voice: Voice): String {
        val voiceName = voice.name ?: "en-US-JennyNeural"
        val lang = voice.primaryLanguage ?: "en-US"
        val pitch = when {
            voice.pitchForSSML != null -> voice.pitchForSSML!!
            voice.pitch != null -> convertPitchToSSML(voice.pitch!!)
            else -> "medium"
        }
        val rate = when {
            voice.rateForSSML != null -> voice.rateForSSML!!
            voice.rate != null -> convertRateToSSML(voice.rate!!)
            else -> "medium"
        }
        
        return """
            <speak version="1.0" xml:lang="$lang">
                <voice xml:lang="$lang" name="$voiceName">
                    <prosody pitch="$pitch" rate="$rate">
                        <lang xml:lang="$lang">
                            $userSSML
                        </lang>
                    </prosody>
                </voice>
            </speak>
        """.trimIndent()
    }
    
    private fun convertPitchToSSML(pitch: Double): String {
        return when {
            pitch < 0.7 -> "x-low"
            pitch < 0.8 -> "low"
            pitch < 1.2 -> "medium"
            pitch < 1.5 -> "high"
            else -> "x-high"
        }
    }
    
    private fun convertRateToSSML(rate: Double): String {
        return when {
            rate < 0.7 -> "x-slow"
            rate < 0.8 -> "slow"
            rate < 1.2 -> "medium"
            rate < 1.5 -> "fast"
            else -> "x-fast"
        }
    }

    private suspend fun speakWithSystemTts(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        log.info("using system TTS for speech synthesis")
        
        val v = voice ?: Voice(name = "system-default", primaryLanguage = "en-US")
        
        // Determine which TTS system to use
        if (stopRequested.get()) {
            log.info("stop requested before system TTS launch; skipping")
            return
        }
        val success = when {
            v.name?.startsWith("espeak-") == true -> speakWithEspeak(text, v, pitch, rate)
            v.name?.startsWith("espeak-ng-") == true -> speakWithEspeakNg(text, v, pitch, rate)
            v.name?.startsWith("festival-") == true -> speakWithFestival(text, v, pitch, rate)
            v.name?.startsWith("say-") == true -> speakWithSay(text, v, pitch, rate)
            isCommandAvailable("espeak-ng") -> speakWithEspeakNg(text, v, pitch, rate)
            isCommandAvailable("espeak") -> speakWithEspeak(text, v, pitch, rate)
            isCommandAvailable("festival") -> speakWithFestival(text, v, pitch, rate)
            isCommandAvailable("say") -> speakWithSay(text, v, pitch, rate)
            else -> {
                log.warn("No system TTS available")
                false
            }
        }
        
        if (!success) {
            throw UnsupportedOperationException("System TTS failed or not available on this platform")
        }
        
        // Record history without audio file path for system TTS
        runCatching {
            val koin = GlobalContext.getOrNull()
            val repo = koin?.get<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
            val now = System.currentTimeMillis()
            repo?.add(
                io.github.jdreioe.wingmate.domain.SaidText(
                    date = now,
                    saidText = text,
                    voiceName = v.name,
                    pitch = pitch,
                    speed = rate,
                    audioFilePath = null, // System TTS doesn't save files
                    createdAt = now,
                    position = 0,
                    primaryLanguage = v.primaryLanguage
                )
            )
        }
    }

    override suspend fun pause() {
        log.info("pause() called")
        withContext(Dispatchers.IO) {
            try {
                if (isPlaying && !isPaused) {
                    // For JLayer player, we need to stop it (no pause support)
                    currentPlayer?.let { player ->
                        log.info("stopping current audio playback")
                        runCatching { player.close() }
                        currentPlayer = null
                    }
                    
                    // For system TTS processes, try to pause/stop them
                    currentProcess?.let { process ->
                        log.info("stopping current system TTS process")
                        runCatching { 
                            // Send SIGTERM to gracefully stop
                            process.destroy()
                            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                            if (process.isAlive) {
                                // Force kill if still running
                                process.destroyForcibly()
                            }
                        }
                        currentProcess = null
                    }
                    
                    // Mark as paused for segmented playback
                    isPaused = true
                    pausedAtSegment = true
                    log.info("speech paused/stopped - can be resumed from segment {}", currentSegmentIndex)
                } else {
                    log.info("no active speech to pause")
                }
            } catch (e: Exception) {
                log.error("error during pause", e)
            }
        }
    }

    override suspend fun stop() {
        log.info("stop() called")
        stopRequested.set(true)
        withContext(Dispatchers.IO) {
            try {
                // Stop any current playback
                currentPlayer?.let { player ->
                    log.info("stopping current audio player")
                    runCatching { player.close() }
                    currentPlayer = null
                }
                
                // Stop any system TTS processes
                currentProcess?.let { process ->
                    log.info("terminating current system TTS process")
                    runCatching {
                        process.destroy()
                        process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
                        if (process.isAlive) {
                            process.destroyForcibly()
                        }
                    }
                    currentProcess = null
                }
                
                isPlaying = false
                isPaused = false
                pausedAtSegment = false
                currentSegments = emptyList()
                currentSegmentIndex = 0
                log.info("speech stopped completely")
            } catch (e: Exception) {
                log.error("error during stop", e)
            }
        }
    }

    override suspend fun resume() {
        log.info("resume() called")
        if (isPaused && pausedAtSegment && currentSegments.isNotEmpty()) {
            log.info("resuming from segment index: {}", currentSegmentIndex)
            isPaused = false
            pausedAtSegment = false
            playSegmentsFromIndex(currentSegmentIndex)
        } else {
            log.info("no paused segments to resume")
        }
    }

    override fun isPlaying(): Boolean = isPlaying

    override fun isPaused(): Boolean = isPaused

    override suspend fun guessPronunciation(text: String, language: String): String? {
        val langCode = language.take(2).lowercase()
        log.info("Guessing pronunciation for: '$text' in language '$langCode' using Wiktionary")
        return try {
            // Use Wiktionary API as a robust source for IPA
            val url = "https://en.wiktionary.org/w/api.php?action=query&titles=${text.trim()}&prop=revisions&rvprop=content&format=json"
            log.info("Fetching URL: $url")
            val response = client.get(url)
            
            if (response.status.value == 200) {
                val body = response.bodyAsText()
                val json = Json { ignoreUnknownKeys = true }
                val root = json.parseToJsonElement(body).jsonObject
                
                val pages = root["query"]?.jsonObject?.get("pages")?.jsonObject
                if (pages == null || pages.isEmpty()) return null
                
                // Wiktionary returns "-1" key for missing pages, or the page ID
                val pageKey = pages.keys.first()
                if (pageKey == "-1") {
                    log.info("Word not found in Wiktionary")
                    return null
                }
                
                val page = pages[pageKey]?.jsonObject
                val revisions = page?.get("revisions")?.jsonArray
                val content = revisions?.get(0)?.jsonObject?.get("*")?.jsonPrimitive?.content
                
                if (content != null) {
                    // Look for {{IPA|lang|/pronunciation/}}
                    // Regex to capture the content between slashes
                    val regex = Regex("\\{\\{IPA\\|$langCode\\|/([^/]+)/")
                    val match = regex.find(content)
                    if (match != null) {
                        val ipa = match.groupValues[1]
                        log.info("Found IPA in Wiktionary: $ipa")
                        return ipa
                    }
                    
                    // Fallback: sometimes it's [pronunciation]
                    val regexBrackets = Regex("\\{\\{IPA\\|$langCode\\|\\[([^\\]]+)\\]")
                    val matchBrackets = regexBrackets.find(content)
                    if (matchBrackets != null) {
                        val ipa = matchBrackets.groupValues[1]
                        log.info("Found IPA (brackets) in Wiktionary: $ipa")
                        return ipa
                    }
                }
            }
            null
        } catch (e: Exception) {
            log.warn("Failed to guess pronunciation for '$text'", e)
            null
        }
    }

    private suspend fun playSegmentsFromIndex(startIndex: Int) {
        log.info("playSegmentsFromIndex() called with startIndex: {}", startIndex)
        
        withContext(Dispatchers.IO) {
            for (i in startIndex until currentSegments.size) {
                if (stopRequested.get()) {
                    log.info("Stop requested, aborting segment playback")
                    break
                }
                
                if (pausedAtSegment) {
                    log.info("Paused at segment, stopping playback")
                    currentSegmentIndex = i
                    break
                }
                
                val segment = currentSegments[i]
                currentSegmentIndex = i
                
                log.info("Playing segment {}/{}: '{}'", i + 1, currentSegments.size, segment.text.take(50))
                
                // Play the text content of this segment
                if (segment.text.isNotEmpty()) {
                    val segmentVoice = currentVoice.applyLanguageOverride(segment.languageTag)
                    playTextSegment(segment.text, segmentVoice, currentPitch, currentRate)
                }
                
                // Apply pause if specified and not stopped/paused
                if (segment.pauseDurationMs > 0 && !stopRequested.get() && !pausedAtSegment) {
                    log.info("Applying pause of {}ms after segment", segment.pauseDurationMs)
                    try {
                        kotlinx.coroutines.delay(segment.pauseDurationMs)
                    } catch (e: Exception) {
                        log.warn("Pause interrupted", e)
                        break
                    }
                }
            }
            
            // Mark as finished if we completed all segments
            if (currentSegmentIndex >= currentSegments.size - 1 && !pausedAtSegment) {
                isPlaying = false
                currentSegments = emptyList()
                currentSegmentIndex = 0
                log.info("Completed playing all segments")
            }
        }
    }

    private suspend fun playTextSegment(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        log.info("playTextSegment() called with text: '{}'", text.take(50))
        
        // Check if user prefers system TTS
        val koin = GlobalContext.getOrNull()
        val settingsRepo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
        val uiSettings = settingsRepo?.let { runCatching { runBlocking { it.get() } }.getOrNull() }
        
        if (uiSettings?.useSystemTts == true) {
            // Use system TTS
            speakWithSystemTts(text, voice, pitch, rate)
            return
        }
        
        // Try to reuse cached Azure audio if available to save API calls
        if (maybePlayFromCache(text, voice, pitch, rate, uiSettings?.primaryLanguage)) {
            return
        }

        // Use Azure TTS (enhanced implementation)
        speakWithAzureTts(text, voice, pitch, rate)
    }

    // Helper methods (implement these with the system TTS functionality)
    private fun isCommandAvailable(command: String): Boolean {
        return try {
            val process = ProcessBuilder("which", command)
                .redirectErrorStream(true)
                .start()
            process.waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun Voice?.applyLanguageOverride(languageTag: String?): Voice? {
        if (languageTag.isNullOrBlank()) return this
        val base = this ?: Voice(name = "en-US-JennyNeural", primaryLanguage = languageTag, selectedLanguage = languageTag.orEmpty())
        return base.copy(
            selectedLanguage = languageTag.orEmpty(),
            primaryLanguage = languageTag
        )
    }

    private suspend fun trySpeakSegmentsWithAzureSsml(
        segments: List<SpeechSegment>,
        voice: Voice?,
        pitch: Double?,
        rate: Double?
    ): Boolean {
        if (segments.isEmpty()) return false
        val combinedText = segments.joinToString(separator = "") { it.text }
        if (combinedText.isBlank()) return false

        val koin = GlobalContext.getOrNull()
        val settingsRepo = koin?.let { runCatching { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }.getOrNull() }
        val uiSettings = settingsRepo?.let { runCatching { runBlocking { it.get() } }.getOrNull() }
        if (uiSettings?.useSystemTts == true) {
            log.info("System TTS preferred; skipping Azure SSML merge path")
            return false
        }

        val cfg = getConfig() ?: return false

        if (maybePlayFromCache(combinedText, voice, pitch, rate, uiSettings?.primaryLanguage)) {
            log.info("Reused cached Azure audio for combined SSML")
            return true
        }

        val baseVoice = voice ?: Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US")
        val enhancedVoice = baseVoice.copy(
            pitch = pitch ?: baseVoice.pitch,
            rate = rate ?: baseVoice.rate
        )
        val effectiveLang = when {
            !enhancedVoice.selectedLanguage.isNullOrBlank() -> enhancedVoice.selectedLanguage
            !uiSettings?.primaryLanguage.isNullOrBlank() -> uiSettings!!.primaryLanguage
            !enhancedVoice.primaryLanguage.isNullOrBlank() -> enhancedVoice.primaryLanguage
            else -> "en-US"
        } ?: "en-US"
        val voiceForSsml = enhancedVoice.copy(primaryLanguage = effectiveLang, selectedLanguage = effectiveLang)

        return try {
            val ssml = AzureTtsClient.generateSsml(segments, voiceForSsml)
            synthesizeAndPlayAzureSsml(ssml, voiceForSsml, cfg, combinedText)
            true
        } catch (t: Throwable) {
            log.warn("Combined Azure SSML path failed; falling back to segmented playback", t)
            false
        }
    }

    private suspend fun synthesizeAndPlayAzureSsml(
        ssml: String,
        voice: Voice,
        cfg: SpeechServiceConfig,
        plainTextForHistory: String
    ) {
        log.info("sending synth request to Azure endpoint (endpoint: ${cfg.endpoint})")
        log.debug("SSML preview: ${ssml.take(200)}...")

        val bytes = AzureTtsClient.synthesize(
            client,
            ssml,
            cfg,
            AzureTtsClient.AudioFormat.MP3_24KHZ_160KBPS
        )
        log.info("received {} bytes from Azure TTS", bytes.size)
        if (stopRequested.get()) {
            log.info("stop requested during synthesis; skipping playback")
            return
        }

        if (bytes.isEmpty()) {
            throw RuntimeException("Azure TTS returned empty audio data")
        }

        val userHome = System.getProperty("user.home")
        val dataDir = Paths.get(userHome, ".local", "share", "wingmate", "audio", "azure")
        if (!Files.exists(dataDir)) Files.createDirectories(dataDir)

        val timestamp = System.currentTimeMillis()
        val safeName = plainTextForHistory.take(32).replace("[^A-Za-z0-9-_ ]".toRegex(), "_").trim().ifBlank { "utterance" }
        val voiceShortName = (voice.name ?: "unknown").split("-").lastOrNull() ?: "default"
        val outPath = dataDir.resolve("${timestamp}_${voiceShortName}_${safeName}.mp3")

        Files.write(outPath, bytes)
        log.info("saved audio to: ${outPath.toAbsolutePath()}")

        recordAzureTtsHistory(plainTextForHistory, voice, outPath.toAbsolutePath().toString(), timestamp)

        if (stopRequested.get()) {
            log.info("stop requested before playback; skipping")
            return
        }
        playAudioFile(outPath.toFile())
    }

    private suspend fun speakWithEspeak(text: String, voice: Voice, pitch: Double?, rate: Double?): Boolean {
        return speakWithEspeakFamily("espeak", text, voice, pitch, rate)
    }
    
    private suspend fun speakWithEspeakNg(text: String, voice: Voice, pitch: Double?, rate: Double?): Boolean {
        return speakWithEspeakFamily("espeak-ng", text, voice, pitch, rate)
    }
    
    private suspend fun speakWithEspeakFamily(espeakCommand: String, text: String, voice: Voice, pitch: Double?, rate: Double?): Boolean {
        return try {
            val command = mutableListOf(espeakCommand)
            
            // Voice selection
            when {
                voice.name?.startsWith("espeak-") == true -> {
                    val voiceName = voice.name!!.removePrefix("espeak-")
                    command.addAll(listOf("-v", voiceName))
                }
                voice.name?.startsWith("espeak-ng-") == true -> {
                    val voiceName = voice.name!!.removePrefix("espeak-ng-")
                    command.addAll(listOf("-v", voiceName))
                }
                else -> {
                    // Use language from voice
                    val lang = voice.primaryLanguage ?: "en-US"
                    command.addAll(listOf("-v", lang))
                }
            }
            
            // Pitch adjustment (espeak uses 0-99 range, default 50)
            pitch?.let { p ->
                val espeakPitch = (p * 50 + 50).coerceIn(0.0, 99.0).toInt()
                command.addAll(listOf("-p", espeakPitch.toString()))
            }
            
            // Rate adjustment (espeak uses words per minute, default ~175)
            rate?.let { r ->
                val espeakRate = (r * 175).coerceIn(50.0, 400.0).toInt()
                command.addAll(listOf("-s", espeakRate.toString()))
            }
            
            command.add(text)
            
            log.info("executing {} command: {}", espeakCommand, command.joinToString(" "))
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            
            currentProcess = process
            isPlaying = true
            isPaused = false
                
            val exitCode = process.waitFor()
            
            currentProcess = null
            isPlaying = false
            
            log.info("{} finished with exit code: {}", espeakCommand, exitCode)
            exitCode == 0
        } catch (e: Exception) {
            log.error("Error with {} TTS", espeakCommand, e)
            currentProcess = null
            isPlaying = false
            false
        }
    }
    
    private suspend fun speakWithFestival(text: String, voice: Voice, pitch: Double?, rate: Double?): Boolean {
        return try {
            log.info("using Festival TTS")
            // Festival uses stdin input
            val process = ProcessBuilder("festival", "--tts")
                .redirectErrorStream(true)
                .start()
            
            currentProcess = process
            isPlaying = true
            isPaused = false
                
            process.outputStream.use { output ->
                output.write(text.toByteArray())
                output.flush()
            }
            
            val exitCode = process.waitFor()
            
            currentProcess = null
            isPlaying = false
            
            log.info("festival finished with exit code: {}", exitCode)
            exitCode == 0
        } catch (e: Exception) {
            log.error("Error with festival TTS", e)
            currentProcess = null
            isPlaying = false
            false
        }
    }
    
    private suspend fun speakWithSay(text: String, voice: Voice, pitch: Double?, rate: Double?): Boolean {
        return try {
            log.info("using macOS say TTS")
            val command = mutableListOf("say")
            
            // Voice selection for macOS
            if (voice.name?.startsWith("say-") == true) {
                val voiceName = voice.name!!.removePrefix("say-")
                command.addAll(listOf("-v", voiceName))
            }
            
            // Rate adjustment (say uses words per minute, default ~175)
            rate?.let { r ->
                val sayRate = (r * 175).coerceIn(50.0, 400.0).toInt()
                command.addAll(listOf("-r", sayRate.toString()))
            }
            
            command.add(text)
            
            log.info("executing say command: {}", command.joinToString(" "))
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            
            currentProcess = process
            isPlaying = true
            isPaused = false
                
            val exitCode = process.waitFor()
            
            currentProcess = null
            isPlaying = false
            
            log.info("say finished with exit code: {}", exitCode)
            exitCode == 0
        } catch (e: Exception) {
            log.error("Error with say TTS", e)
            currentProcess = null
            isPlaying = false
            false
        }
    }

    private suspend fun getConfig(): SpeechServiceConfig? {
        // Get Azure config from repository or env vars
        val koin = GlobalContext.getOrNull()
        val repo = koin?.let { runCatching { it.get<ConfigRepository>() }.getOrNull() }
        val config = repo?.let { 
            runCatching { 
                withContext(Dispatchers.IO) { it.getSpeechConfig() } 
            }.getOrNull() 
        }
        
        if (config != null) {
            log.debug("loaded Azure config from repository (endpoint: ${config.endpoint})")
            return config
        }

        // Fallback to environment variables
        val endpoint = System.getenv("WINGMATE_AZURE_REGION")?.takeIf { it.isNotBlank() }
        val key = System.getenv("WINGMATE_AZURE_KEY")?.takeIf { it.isNotBlank() }
        
        return if (endpoint != null && key != null) {
            log.debug("loaded Azure config from environment variables (endpoint: $endpoint)")
            SpeechServiceConfig(endpoint = endpoint, subscriptionKey = key)
        } else {
            log.warn("no Azure TTS configuration found - neither in repository nor environment variables")
            null
        }
    }

    private suspend fun recordAzureTtsHistory(text: String, voice: Voice, audioFilePath: String, timestamp: Long) {
        runCatching {
            val koin = GlobalContext.getOrNull()
            val repo = koin?.get<io.github.jdreioe.wingmate.domain.SaidTextRepository>()
            repo?.add(
                io.github.jdreioe.wingmate.domain.SaidText(
                    date = timestamp,
                    saidText = text,
                    voiceName = voice.name,
                    pitch = voice.pitch,
                    speed = voice.rate,
                    audioFilePath = audioFilePath,
                    createdAt = timestamp,
                    position = 0,
                    primaryLanguage = voice.selectedLanguage?.takeIf { it.isNotBlank() } ?: voice.primaryLanguage
                )
            )
            log.debug("recorded TTS history entry for voice: ${voice.name}")
        }.onFailure { e ->
            log.warn("failed to record TTS history", e)
        }
    }

    private suspend fun playAudioFile(audioFile: File) {
        try {
            if (!audioFile.exists()) {
                throw RuntimeException("Audio file does not exist: ${audioFile.absolutePath}")
            }
            
            if (audioFile.length() == 0L) {
                throw RuntimeException("Audio file is empty: ${audioFile.absolutePath}")
            }
            
            log.info("attempting to play audio file: ${audioFile.absolutePath} (${audioFile.length()} bytes)")
            
            if (stopRequested.get()) {
                log.info("stop requested before play; aborting")
                return
            }

            // Determine whether to route audio to virtual mic
            val routeToVirtual = shouldRouteToVirtualMic()
            val sinkArg = if (routeToVirtual) {
                ensureVirtualMic()
                virtualSinkName
            } else null

            if (stopRequested.get()) {
                log.info("stop requested before audio playback start; aborting")
                return
            }

            // For MP3 files, use ffplay directly as it has better compatibility
            if (audioFile.extension.lowercase() == "mp3") {
                log.info("MP3 file detected, using ffplay for playback")
                playWithSystemCommand(audioFile, sinkArg)
                return
            }

            // For WAV files, try Java Sound API first
            if (!routeToVirtual && audioFile.extension.lowercase() == "wav") {
                try {
                    log.info("trying Java Sound API for WAV playback")
                    
                    val audioInputStream = AudioSystem.getAudioInputStream(audioFile)
                    val format = audioInputStream.format
                    log.info("Audio format: sampleRate=${format.sampleRate}, channels=${format.channels}, encoding=${format.encoding}")
                    
                    val clip = AudioSystem.getClip()
                    
                    var playbackCompleted = false
                    clip.addLineListener { event ->
                        when (event.type) {
                            LineEvent.Type.STOP -> {
                                playbackCompleted = true
                                log.info("Audio playback stopped event received")
                            }
                            LineEvent.Type.START -> {
                                log.info("Audio playback started")
                            }
                            else -> {}
                        }
                    }
                    
                    clip.open(audioInputStream)
                    isPlaying = true
                    isPaused = false
                    
                    log.info("starting Java Sound API playback on thread=${Thread.currentThread().name}")
                    clip.start()
                    
                    while (!playbackCompleted && clip.isRunning && !stopRequested.get()) {
                        Thread.sleep(50)
                    }
                    
                    if (stopRequested.get()) {
                        log.info("Stop requested during playback")
                        clip.stop()
                    }
                    
                    Thread.sleep(100)
                    
                    clip.close()
                    audioInputStream.close()
                    isPlaying = false
                    
                    log.info("Java Sound API playback completed")
                    return
                } catch (e: Exception) {
                    log.warn("Java Sound API playback failed, trying system commands", e)
                    isPlaying = false
                }
            }
            
            // Fallback to system commands
            playWithSystemCommand(audioFile, sinkArg)
        } catch (e: Exception) {
            log.error("error playing audio file", e)
            isPlaying = false
        }
    }

    private suspend fun playWithSystemCommand(audioFile: File, sinkArg: String?) {
        withContext(Dispatchers.IO) {
            // Try system audio commands
            val commands = listOf(
                // ffplay with volume boost and better audio driver selection for PipeWire/PulseAudio
                listOf("ffplay", "-nodisp", "-autoexit", "-volume", "100", audioFile.absolutePath),
                listOf("mpg123", audioFile.absolutePath),
                listOf("paplay", audioFile.absolutePath)
            )
            
            for (command in commands) {
                if (isCommandAvailable(command[0])) {
                    try {
                        if (stopRequested.get()) {
                            log.info("stop requested before system player start; aborting")
                            return@withContext
                        }
                        log.info("trying system command: {}", command.joinToString(" "))
                        val pb = ProcessBuilder(command)
                        if (sinkArg != null) {
                            val env = pb.environment()
                            env["PULSE_SINK"] = sinkArg
                            log.info("routing playback to virtual sink: {}", sinkArg)
                        }
                        val process = pb
                            .redirectErrorStream(true)
                            .start()
                        
                        currentProcess = process
                        isPlaying = true
                        isPaused = false
                        
                        val exitCode = process.waitFor()
                        
                        currentProcess = null
                        isPlaying = false
                        
                        if (exitCode == 0) {
                            log.info("system audio player succeeded: {}", command[0])
                            return@withContext
                        } else {
                            log.warn("system audio player failed with exit code {}: {}", exitCode, command[0])
                        }
                    } catch (e: Exception) {
                        log.warn("error with system audio player {}", command[0], e)
                        currentProcess = null
                        isPlaying = false
                    }
                }
            }
            
            log.error("no working audio player found for file: {}", audioFile.absolutePath)
        }
    }

    private fun shouldRouteToVirtualMic(): Boolean {
        return runCatching {
            val koin = GlobalContext.getOrNull()
            val settingsRepo = koin?.let { it.get<io.github.jdreioe.wingmate.domain.SettingsRepository>() }
            val settings = runCatching { runBlocking { settingsRepo?.get() } }.getOrNull()
            settings?.virtualMicEnabled == true
        }.getOrNull() == true
    }

    private fun ensureVirtualMic() {
        // Only attempt on Linux with PulseAudio or PipeWire's Pulse server
        val os = System.getProperty("os.name").lowercase()
        if (!os.contains("linux")) return
        if (!isCommandAvailable("pactl")) {
            log.warn("pactl not found; cannot create virtual mic sink")
            return
        }

        try {
            // Detect whether the Pulse server is PipeWire-backed
            val info = ProcessBuilder("pactl", "info").redirectErrorStream(true).start()
            val infoOut = info.inputStream.bufferedReader().readText()
            val isPipeWire = infoOut.contains("PipeWire", ignoreCase = true)
            log.info("Pulse server: {}", infoOut.lineSequence().firstOrNull { it.contains("Server Name", true) } ?: "<unknown>")

            // Check if sink already exists
            val listSinks = ProcessBuilder("pactl", "list", "short", "sinks")
                .redirectErrorStream(true)
                .start()
            val sinksOut = listSinks.inputStream.bufferedReader().readText()
            if (!sinksOut.lines().any { it.contains(virtualSinkName) }) {
                // Create null sink; also set a readable monitor name via source_properties
                log.info("creating PulseAudio null sink: {}", virtualSinkName)
                val createSink = ProcessBuilder(
                    "pactl", "load-module", "module-null-sink",
                    "sink_name=$virtualSinkName",
                    "sink_properties=device.description=$virtualSinkDesc",
                    "source_properties=device.description=${virtualSinkDesc} (Monitor)"
                ).redirectErrorStream(true).start()
                createSink.waitFor()
                log.info(
                    "created null sink (exit={}): {}",
                    createSink.exitValue(),
                    createSink.inputStream.bufferedReader().readText()
                )
            }

            // Create a dedicated microphone source that maps to the sink's monitor, so apps see it as a mic
            val remapSourceName = "${virtualSinkName}_mic"
            val listSources = ProcessBuilder("pactl", "list", "short", "sources")
                .redirectErrorStream(true)
                .start()
            val sourcesOut = listSources.inputStream.bufferedReader().readText()
            if (!sourcesOut.lines().any { it.contains(remapSourceName) }) {
                if (isPipeWire) {
                    // Prefer module-virtual-source under PipeWire to avoid remap-source crashes
                    log.info("creating virtual-source mic (PipeWire): {} -> {}.monitor", remapSourceName, virtualSinkName)
                    val createVsrc = ProcessBuilder(
                        "pactl", "load-module", "module-virtual-source",
                        "source_name=$remapSourceName",
                        "master=${virtualSinkName}.monitor",
                        "source_properties=device.description=$virtualSinkDesc"
                    ).redirectErrorStream(true).start()
                    createVsrc.waitFor()
                    val vsrcOut = createVsrc.inputStream.bufferedReader().readText()
                    if (createVsrc.exitValue() == 0) {
                        log.info("created virtual-source mic (exit={}): {}", createVsrc.exitValue(), vsrcOut)
                    } else {
                        log.warn("failed to create virtual-source mic (exit={}): {} — falling back to monitor-only", createVsrc.exitValue(), vsrcOut)
                    }
                } else {
                    // PulseAudio classic: remap-source is fine
                    log.info("creating remap-source as microphone: {} -> {}.monitor", remapSourceName, virtualSinkName)
                    val createSource = ProcessBuilder(
                        "pactl", "load-module", "module-remap-source",
                        "source_name=$remapSourceName",
                        "master=${virtualSinkName}.monitor",
                        "source_properties=device.description=$virtualSinkDesc"
                    ).redirectErrorStream(true).start()
                    createSource.waitFor()
                    log.info(
                        "created remap-source mic (exit={}): {}",
                        createSource.exitValue(),
                        createSource.inputStream.bufferedReader().readText()
                    )
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to ensure virtual mic sink/source — using monitor-only fallback", e)
        }
    }
}
