package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.Voice
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
) : SpeechService {

    init {
        configureAudioSession()
    }

    private fun configureAudioSession() {
        // No-op in common metadata; the Swift app can configure the session at runtime.
    }

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        val normalizedText = SpeechTextProcessor.normalizeShorthandSsml(text)
        if (normalizedText.isBlank()) return

        val effectiveVoice = voice ?: defaultVoice()
        val audioBytes = synthesize(normalizedText, effectiveVoice) ?: return
        playAudio(audioBytes)
        trySaveHistory(normalizedText, effectiveVoice, pitch, rate, null)
    }

    override suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) {
        if (segments.isEmpty()) return
        val combinedText = segments.joinToString(separator = "") { it.text }
        val effectiveVoice = voice ?: defaultVoice()
        val audioBytes = synthesizeSegments(segments, effectiveVoice) ?: return
        playAudio(audioBytes)
        trySaveHistory(combinedText, effectiveVoice, pitch, rate, null)
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
            val url = "https://en.wiktionary.org/w/api.php?action=query&titles=${text.trim()}&prop=revisions&rvprop=content&format=json"
            val response = httpClient.get(url)
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
                val regex = Regex("\\{\\{IPA\\|$langCode\\|/([^/]+)/")
                regex.find(content)?.groupValues?.getOrNull(1)?.let { return it }
                val regexBrackets = Regex("\\{\\{IPA\\|$langCode\\|\\[([^\\]]+)\\]")
                regexBrackets.find(content)?.groupValues?.getOrNull(1)?.let { return it }
            }
            null
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
            repo.add(
                SaidText(
                    date = now,
                    saidText = text,
                    voiceName = voice?.name ?: voice?.displayName,
                    pitch = pitch ?: voice?.pitch,
                    speed = rate ?: voice?.rate,
                    audioFilePath = filePath,
                    createdAt = now,
                    primaryLanguage = voice?.selectedLanguage ?: voice?.primaryLanguage
                )
            )
        }
    }
}
