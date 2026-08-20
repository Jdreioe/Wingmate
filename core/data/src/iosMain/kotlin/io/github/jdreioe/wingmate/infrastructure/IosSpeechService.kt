package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.ConfigRepository
import io.github.jdreioe.wingmate.domain.PronunciationDictionaryRepository
import io.github.jdreioe.wingmate.domain.SaidText
import io.github.jdreioe.wingmate.domain.SaidTextRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.SpeechPlaybackState
import io.github.jdreioe.wingmate.domain.SpeechPlaybackStatus
import io.github.jdreioe.wingmate.domain.SpeechTextProcessor
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceProvider
import io.github.jdreioe.wingmate.domain.resolvedProvider
import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.domain.OperationalLogger
import io.github.jdreioe.wingmate.domain.loggingClassName
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.AVFAudio.AVAudioPlayer
import platform.AVFAudio.AVAudioPlayerDelegateProtocol
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosSpeechService(
    private val httpClient: HttpClient,
    private val configRepository: ConfigRepository,
    private val pronunciationDictionaryRepository: PronunciationDictionaryRepository? = null,
    private val saidRepo: SaidTextRepository? = null,
    private val settingsRepository: SettingsRepository? = null,
    private val voiceRepository: VoiceRepository? = null,
    private val googleApiRequestHeaders: GoogleApiRequestHeaders = NoGoogleApiRequestHeaders,
) : SpeechService {

    private val sentenceAudioCache = mutableMapOf<String, ByteArray>()
    private val sentenceAudioCacheMutex = Mutex()
    private data class PendingCache(val text: String, val voice: Voice?, val pitch: Double?, val rate: Double?)
    private val pendingSpeechCache = mutableSetOf<PendingCache>()
    private val pendingSpeechCacheMutex = Mutex()
    private var currentPlayer: AVAudioPlayer? = null
    private var currentPlayerRequestId: Long = 0
    private var requestGeneration: Long = 0
    private var activeRequestJob: Job? = null
    private var state = SpeechPlaybackState()
    private val playerDelegate = object : NSObject(), AVAudioPlayerDelegateProtocol {
        override fun audioPlayerDidFinishPlaying(player: AVAudioPlayer, successfully: Boolean) {
            handlePlayerFinished(player, successfully)
        }
    }

    init {
        configureAudioSession()
    }

    private fun configureAudioSession() {
        // No-op in common metadata; the Swift app can configure the session at runtime.
    }

    private suspend fun beginRequest(): Long = withContext(Dispatchers.Main) {
        val job = currentCoroutineContext()[Job]
        activeRequestJob?.takeIf { it !== job }?.cancel()
        activeRequestJob = job
        requestGeneration += 1
        currentPlayer?.stop()
        currentPlayer = null
        currentPlayerRequestId = 0
        state = SpeechPlaybackState(requestGeneration, SpeechPlaybackStatus.PREPARING)
        requestGeneration
    }

    private suspend fun <T> executeRequest(requestId: Long, block: suspend () -> T): T = try {
        block()
    } catch (cancelled: CancellationException) {
        if (requestGeneration == requestId) {
            state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.IDLE)
        }
        throw cancelled
    } catch (error: Throwable) {
        failRequest(requestId)
        throw error
    }

    private fun failRequest(requestId: Long) {
        if (requestGeneration == requestId) {
            state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.FAILED, "Speech could not be played")
        }
    }

    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) {
        speakWithCachePolicy(text, voice, pitch, rate, cacheAudio = true)
    }

    override suspend fun speakWithCachePolicy(text: String, voice: Voice?, pitch: Double?, rate: Double?, cacheAudio: Boolean) {
        val normalizedText = SpeechTextProcessor.normalizeShorthandSsml(text)
        if (normalizedText.isBlank()) return
        val requestId = beginRequest()

        executeRequest(requestId) {
            val engine = settingsRepository?.get()?.ttsEngine ?: TtsEngine.AZURE_USER_RESOURCE
            val effectiveVoice = (voice ?: defaultVoice()).forCloudProvider(engine).let { base ->
                base.copy(pitch = pitch ?: base.pitch, rate = rate ?: base.rate)
            }
            if (engine == TtsEngine.SYSTEM) error("System speech is handled by the iOS host")
            val cacheKey = "${engine.name}|text|$normalizedText|${effectiveVoice.name}|${effectiveVoice.primaryLanguage}|${effectiveVoice.selectedLanguage}|$pitch|$rate|math=${effectiveVoice.mathMode}"
            val cached = if (cacheAudio) sentenceAudioCacheMutex.withLock { sentenceAudioCache[cacheKey] } else null
            val audioBytes = cached ?: synthesize(normalizedText, effectiveVoice)
                ?: error("The selected cloud speech engine is not configured")
            if (cacheAudio && cached == null) sentenceAudioCacheMutex.withLock { sentenceAudioCache[cacheKey] = audioBytes }
            playAudio(requestId, audioBytes)
            trySaveHistory(normalizedText, effectiveVoice, pitch, rate, null)
        }
    }

    override suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) {
        speakSegmentsWithCachePolicy(segments, voice, pitch, rate, cacheAudio = true)
    }

    override suspend fun speakSegmentsWithCachePolicy(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?, cacheAudio: Boolean) {
        if (segments.isEmpty()) return
        val requestId = beginRequest()
        executeRequest(requestId) {
            val combinedText = segments.joinToString(separator = "") { it.text }
            val engine = settingsRepository?.get()?.ttsEngine ?: TtsEngine.AZURE_USER_RESOURCE
            val effectiveVoice = (voice ?: defaultVoice()).forCloudProvider(engine).let { base ->
                base.copy(pitch = pitch ?: base.pitch, rate = rate ?: base.rate)
            }
            if (engine == TtsEngine.SYSTEM) error("System speech is handled by the iOS host")
            val cacheKey = "${engine.name}|segments|${segments.joinToString()}|${effectiveVoice.name}|${effectiveVoice.primaryLanguage}|${effectiveVoice.selectedLanguage}|$pitch|$rate|math=${effectiveVoice.mathMode}"
            val cached = if (cacheAudio) sentenceAudioCacheMutex.withLock { sentenceAudioCache[cacheKey] } else null
            val audioBytes = cached ?: synthesizeSegments(segments, effectiveVoice)
                ?: error("The selected cloud speech engine is not configured")
            if (cacheAudio && cached == null) sentenceAudioCacheMutex.withLock { sentenceAudioCache[cacheKey] = audioBytes }
            playAudio(requestId, audioBytes)
            trySaveHistory(combinedText, effectiveVoice, pitch, rate, null)
        }
    }

    override suspend fun cacheSpeech(text: String, voice: Voice?, pitch: Double?, rate: Double?): Boolean {
        val normalizedText = SpeechTextProcessor.normalizeShorthandSsml(text).trim()
        if (normalizedText.isEmpty()) return true
        if (settingsRepository?.get()?.ttsEngine == TtsEngine.SYSTEM) return false
        val engine = settingsRepository?.get()?.ttsEngine ?: TtsEngine.AZURE_USER_RESOURCE
        val effectiveVoice = (voice ?: defaultVoice()).forCloudProvider(engine).let { base ->
            base.copy(pitch = pitch ?: base.pitch, rate = rate ?: base.rate)
        }
        val cacheKey = "${engine.name}|text|$normalizedText|${effectiveVoice.name}|${effectiveVoice.primaryLanguage}|${effectiveVoice.selectedLanguage}|$pitch|$rate|math=${effectiveVoice.mathMode}"
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
        val requestId = beginRequest()
        return runCatching {
            playFile(requestId, audioFilePath)
            val spokenText = textForHistory?.trim().orEmpty()
            if (spokenText.isNotEmpty()) {
                val selectedVoice = voice ?: defaultVoice()
                trySaveHistory(spokenText, selectedVoice, selectedVoice.pitch, selectedVoice.rate, audioFilePath)
            }
            true
        }.onFailure { error ->
            if (error is CancellationException) throw error
            failRequest(requestId)
            OperationalLogger.warn("speech_recording.play", "failed")
        }.getOrDefault(false)
    }

    override suspend fun pause() = withContext(Dispatchers.Main) {
        currentPlayer?.takeIf { it.playing }?.let {
            it.pause()
            state = SpeechPlaybackState(currentPlayerRequestId, SpeechPlaybackStatus.PAUSED)
        }
        Unit
    }

    override suspend fun stop() = withContext(Dispatchers.Main) {
        requestGeneration += 1
        activeRequestJob?.cancel()
        activeRequestJob = null
        currentPlayer?.stop()
        currentPlayer = null
        currentPlayerRequestId = 0
        state = SpeechPlaybackState(requestGeneration, SpeechPlaybackStatus.IDLE)
    }

    override suspend fun resume() = withContext(Dispatchers.Main) {
        val player = currentPlayer ?: return@withContext
        if (state.status != SpeechPlaybackStatus.PAUSED) return@withContext
        if (!player.play()) {
            failRequest(currentPlayerRequestId)
            error("Audio playback could not be resumed")
        }
        state = SpeechPlaybackState(currentPlayerRequestId, SpeechPlaybackStatus.PLAYING)
        Unit
    }

    override fun isPlaying(): Boolean = currentPlayer?.playing == true && state.status == SpeechPlaybackStatus.PLAYING

    override fun isPaused(): Boolean = currentPlayer != null && state.status == SpeechPlaybackStatus.PAUSED

    override fun playbackState(): SpeechPlaybackState = state

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
            OperationalLogger.warn("pronunciation.lookup", "failed")
            null
        }
    }

    private suspend fun synthesize(text: String, voice: Voice): ByteArray? = withContext(Dispatchers.Default) {
        when (settingsRepository?.get()?.ttsEngine ?: TtsEngine.AZURE_USER_RESOURCE) {
            TtsEngine.GOOGLE_CLOUD -> {
                val config = configRepository.getGoogleSpeechConfig() ?: return@withContext null
                GoogleTtsClient.synthesize(
                    httpClient,
                    text,
                    voice,
                    config,
                    applicationHeaders = googleApiRequestHeaders,
                )
            }
            TtsEngine.AZURE_USER_RESOURCE, TtsEngine.AZURE_MANAGED -> {
                val config = configRepository.getSpeechConfig() ?: return@withContext null
                val dict = pronunciationDictionaryRepository?.getAll().orEmpty()
                val ssml = AzureTtsClient.generateSsml(text, voice.forAzureProvider(), dict)
                AzureTtsClient.synthesize(httpClient, ssml, config)
            }
            TtsEngine.SYSTEM -> null
        }
    }

    private suspend fun synthesizeSegments(segments: List<SpeechSegment>, voice: Voice): ByteArray? = withContext(Dispatchers.Default) {
        when (settingsRepository?.get()?.ttsEngine ?: TtsEngine.AZURE_USER_RESOURCE) {
            TtsEngine.GOOGLE_CLOUD -> {
                val config = configRepository.getGoogleSpeechConfig() ?: return@withContext null
                GoogleTtsClient.synthesizeSegments(
                    httpClient,
                    segments,
                    voice,
                    config,
                    applicationHeaders = googleApiRequestHeaders,
                )
            }
            TtsEngine.AZURE_USER_RESOURCE, TtsEngine.AZURE_MANAGED -> {
                val config = configRepository.getSpeechConfig() ?: return@withContext null
                val dict = pronunciationDictionaryRepository?.getAll().orEmpty()
                val ssml = AzureTtsClient.generateSsml(segments, voice.forAzureProvider(), dict)
                AzureTtsClient.synthesize(httpClient, ssml, config)
            }
            TtsEngine.SYSTEM -> null
        }
    }

    private suspend fun playFile(requestId: Long, path: String) = withContext(Dispatchers.Main) {
        check(requestGeneration == requestId) { "Speech request was replaced" }
        val url = NSURL.fileURLWithPath(path)
        val audioPlayer = AVAudioPlayer(contentsOfURL = url, error = null)
        startPlayer(requestId, audioPlayer)
    }

    private fun Voice.forAzureProvider(): Voice =
        if (resolvedProvider() == VoiceProvider.GOOGLE || name == "system-default") {
            copy(name = "en-US-JennyNeural")
        } else {
            this
        }

    private fun Voice.forCloudProvider(engine: TtsEngine): Voice {
        val language = selectedLanguage.takeIf(String::isNotBlank)
            ?: primaryLanguage?.takeIf(String::isNotBlank)
            ?: "en-US"
        return when (engine) {
            TtsEngine.GOOGLE_CLOUD -> if (resolvedProvider() == VoiceProvider.GOOGLE) this else {
                Voice(primaryLanguage = language, selectedLanguage = language)
            }
            TtsEngine.AZURE_USER_RESOURCE,
            TtsEngine.AZURE_MANAGED,
            -> if (isAzureCloudVoice()) this else {
                Voice(name = "en-US-JennyNeural", primaryLanguage = language, selectedLanguage = language)
            }
            TtsEngine.SYSTEM -> this
        }
    }

    private fun Voice.isAzureCloudVoice(): Boolean =
        provider == VoiceProvider.AZURE ||
            (provider == null && resolvedProvider() == VoiceProvider.AZURE && name?.contains("Neural") == true)

    private suspend fun playAudio(requestId: Long, audioBytes: ByteArray) = withContext(Dispatchers.Main) {
        require(audioBytes.isNotEmpty()) { "Speech synthesis returned no audio" }
        check(requestGeneration == requestId) { "Speech request was replaced" }
        val data = audioBytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = audioBytes.size.toULong())
        }
        val audioPlayer = AVAudioPlayer(data = data, error = null)
        startPlayer(requestId, audioPlayer)
    }

    private fun startPlayer(requestId: Long, audioPlayer: AVAudioPlayer) {
        audioPlayer.delegate = playerDelegate
        check(audioPlayer.prepareToPlay()) { "Audio playback could not be prepared" }
        currentPlayer = audioPlayer
        currentPlayerRequestId = requestId
        if (!audioPlayer.play()) {
            currentPlayer = null
            currentPlayerRequestId = 0
            error("Audio playback could not be started")
        }
        state = SpeechPlaybackState(requestId, SpeechPlaybackStatus.PLAYING)
    }

    private fun handlePlayerFinished(player: AVAudioPlayer, successfully: Boolean) {
        if (currentPlayer !== player) return
        val requestId = currentPlayerRequestId
        currentPlayer = null
        currentPlayerRequestId = 0
        state = if (successfully) {
            SpeechPlaybackState(requestId, SpeechPlaybackStatus.IDLE)
        } else {
            SpeechPlaybackState(requestId, SpeechPlaybackStatus.FAILED, "Speech playback failed")
        }
    }

    private suspend fun defaultVoice(): Voice =
        runCatching { voiceRepository?.getSelected() }.getOrNull()
            ?: Voice(
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
