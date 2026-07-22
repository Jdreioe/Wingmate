package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSFileManager

private val logger = KotlinLogging.logger {}

@OptIn(ExperimentalForeignApi::class)
class IosSpeechService(
    private val httpClient: HttpClient,
    private val configRepository: ConfigRepository,
    private val pronunciationDictionaryRepository: PronunciationDictionaryRepository? = null,
    private val saidRepo: SaidTextRepository? = null,
    private val settingsRepository: SettingsRepository? = null,
) : SpeechService {

    private val sentenceAudioCache = mutableMapOf<String, ByteArray>()
    private val sentenceAudioCacheMutex = Mutex()
    private data class PendingCache(val text: String, val voice: Voice?, val pitch: Double?, val rate: Double?)
    private val pendingSpeechCache = mutableSetOf<PendingCache>()
    private val pendingSpeechCacheMutex = Mutex()

    init {
        configureAudioSession()
    }

    private fun configureAudioSession() {
        // No-op in common metadata; the Swift app can configure the session at runtime.
    }

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        speakWithCachePolicy(text, voice, pitch, rate, cacheAudio = true)
    }

    override suspend fun speakWithCachePolicy(text: String, voice: Voice?, pitch: Double?, rate: Double?, cacheAudio: Boolean) {
        val normalizedText = SpeechTextProcessor.normalizeShorthandSsml(text)
        if (normalizedText.isBlank()) return

        val effectiveVoice = voice ?: defaultVoice()
        val cacheKey = "text|$normalizedText|${effectiveVoice.name}|$pitch|$rate"
        val cached = if (cacheAudio) sentenceAudioCacheMutex.withLock { sentenceAudioCache[cacheKey] } else null
        val audioBytes = cached ?: synthesize(normalizedText, effectiveVoice) ?: return
        if (cacheAudio && cached == null) sentenceAudioCacheMutex.withLock { sentenceAudioCache[cacheKey] = audioBytes }
        playAudio(audioBytes)
        trySaveHistory(normalizedText, effectiveVoice, pitch, rate, null)
    }

    override suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) {
        speakSegmentsWithCachePolicy(segments, voice, pitch, rate, cacheAudio = true)
    }

    override suspend fun speakSegmentsWithCachePolicy(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?, cacheAudio: Boolean) {
        if (segments.isEmpty()) return
        val combinedText = segments.joinToString(separator = "") { it.text }
        val effectiveVoice = voice ?: defaultVoice()
        val cacheKey = "segments|${segments.joinToString()}|${effectiveVoice.name}|$pitch|$rate"
        val cached = if (cacheAudio) sentenceAudioCacheMutex.withLock { sentenceAudioCache[cacheKey] } else null
        val audioBytes = cached ?: synthesizeSegments(segments, effectiveVoice) ?: return
        if (cacheAudio && cached == null) sentenceAudioCacheMutex.withLock { sentenceAudioCache[cacheKey] = audioBytes }
        playAudio(audioBytes)
        trySaveHistory(combinedText, effectiveVoice, pitch, rate, null)
    }

    override suspend fun cacheSpeech(text: String, voice: Voice?, pitch: Double?, rate: Double?): Boolean {
        val normalizedText = SpeechTextProcessor.normalizeShorthandSsml(text).trim()
        if (normalizedText.isEmpty()) return true
        if (settingsRepository?.get()?.ttsEngine == TtsEngine.SYSTEM) return false
        val effectiveVoice = voice ?: defaultVoice()
        val cacheKey = "text|$normalizedText|${effectiveVoice.name}|$pitch|$rate"
        if (sentenceAudioCacheMutex.withLock { cacheKey in sentenceAudioCache }) return true

        val request = PendingCache(normalizedText, voice, pitch, rate)
        val bytes = runCatching { synthesize(normalizedText, effectiveVoice) }.getOrNull()
        if (bytes == null) {
            pendingSpeechCacheMutex.withLock { pendingSpeechCache += request }
            return false
        }
        sentenceAudioCacheMutex.withLock { sentenceAudioCache[cacheKey] = bytes }
        pendingSpeechCacheMutex.withLock { pendingSpeechCache -= request }
        return true
    }

    override suspend fun retryPendingSpeechCache() {
        val requests = pendingSpeechCacheMutex.withLock { pendingSpeechCache.toList() }
        requests.forEach { cacheSpeech(it.text, it.voice, it.pitch, it.rate) }
    }

    override suspend fun speakRecordedAudio(audioFilePath: String, textForHistory: String?, voice: Voice?): Boolean {
        if (!NSFileManager.defaultManager.fileExistsAtPath(audioFilePath)) return false
        return runCatching {
            playFile(audioFilePath)
            val spokenText = textForHistory?.trim().orEmpty()
            if (spokenText.isNotEmpty()) {
                val selectedVoice = voice ?: defaultVoice()
                trySaveHistory(spokenText, selectedVoice, selectedVoice.pitch, selectedVoice.rate, audioFilePath)
            }
            true
        }.getOrElse { t ->
            logger.warn(t) { "Failed to play recorded audio file" }
            false
        }
    }

    override suspend fun pause() = withContext(Dispatchers.Main) {
        // No-op on iOS for now.
    }

    override suspend fun stop() = withContext(Dispatchers.Main) {
        // No-op on iOS for now.
    }

    override suspend fun resume() {
        // No-op on iOS for now.
    }

    override fun isPlaying(): Boolean = false

    override fun isPaused(): Boolean = false

    override suspend fun guessPronunciation(text: String, language: String): String? {
        val langCode = language.take(2).lowercase()
        return try {
            suspend fun lookup(edition: String, requireLanguageTag: Boolean): String? {
            val response = httpClient.get("https://$edition.wiktionary.org/w/api.php") {
                url { parameters.append("action", "query"); parameters.append("titles", text.trim()); parameters.append("prop", "revisions"); parameters.append("rvprop", "content"); parameters.append("format", "json") }
            }
            if (response.status.value != 200) return null

            val body = response.bodyAsText()
            val json = Json { ignoreUnknownKeys = true }
            val root = json.parseToJsonElement(body).jsonObject
            val pages = root["query"]?.jsonObject?.get("pages")?.jsonObject ?: return null
            val pageKey = pages.keys.firstOrNull() ?: return null
            if (pageKey == "-1") return null

            val page = pages[pageKey]?.jsonObject
            val revisions = page?.get("revisions")?.jsonArray
            val content = revisions?.getOrNull(0)?.jsonObject?.get("*")?.jsonPrimitive?.content
            if (content != null) {
                val regex = if (requireLanguageTag) Regex("\\{\\{IPA\\|$langCode\\|/([^/]+)/") else Regex("\\{\\{IPA\\|(?:$langCode\\|)?/([^/]+)/")
                regex.find(content)?.groupValues?.getOrNull(1)?.let { return it }
                val regexBrackets = Regex("\\{\\{IPA\\|$langCode\\|\\[([^\\]]+)\\]")
                regexBrackets.find(content)?.groupValues?.getOrNull(1)?.let { return it }
            }
            return null
            }
            lookup(langCode, requireLanguageTag = false) ?: if (langCode != "en") lookup("en", requireLanguageTag = true) else null
        } catch (e: Exception) {
            logger.warn(e) { "Failed to guess pronunciation for '$text'" }
            null
        }
    }

    private suspend fun synthesize(text: String, voice: Voice): ByteArray? = withContext(Dispatchers.Default) {
        val config = configRepository.getSpeechConfig() ?: return@withContext null
        val dict = pronunciationDictionaryRepository?.getAll().orEmpty()
        val ssml = AzureTtsClient.generateSsml(text, voice, dict)
        AzureTtsClient.synthesize(httpClient, ssml, config)
    }

    private suspend fun synthesizeSegments(segments: List<SpeechSegment>, voice: Voice): ByteArray? = withContext(Dispatchers.Default) {
        val config = configRepository.getSpeechConfig() ?: return@withContext null
        val dict = pronunciationDictionaryRepository?.getAll().orEmpty()
        val ssml = AzureTtsClient.generateSsml(segments, voice, dict)
        AzureTtsClient.synthesize(httpClient, ssml, config)
    }

    private suspend fun playFile(path: String) = withContext(Dispatchers.Main) {
        // Playback is intentionally a no-op in the shared iOS metadata implementation.
    }

    private suspend fun playAudio(audioBytes: ByteArray) = withContext(Dispatchers.Main) {
        // Playback is intentionally a no-op in the shared iOS metadata implementation.
    }

    private fun defaultVoice(): Voice = Voice(
        id = null,
        name = "en-US-JennyNeural",
        displayName = "Default Voice",
        primaryLanguage = "en-US",
        selectedLanguage = "en-US"
    )

    private suspend fun trySaveHistory(text: String, voice: Voice?, pitch: Double?, rate: Double?, filePath: String?) {
        val repo = saidRepo ?: return
        runCatching {
            val now = Clock.System.now().toEpochMilliseconds()
            val visibleInHistory = settingsRepository?.get()?.historyVisible ?: true
            repo.add(
                SaidText(
                    date = now,
                    saidText = text,
                    voiceName = voice?.name ?: voice?.displayName,
                    pitch = pitch ?: voice?.pitch,
                    speed = rate ?: voice?.rate,
                    audioFilePath = filePath,
                    createdAt = now,
                    primaryLanguage = voice?.selectedLanguage ?: voice?.primaryLanguage,
                    visibleInHistory = visibleInHistory
                )
            )
        }
    }
}
