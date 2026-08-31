package io.github.jdreioe.wingmate.domain


import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet

interface BoardRepository {
    suspend fun getBoard(id: String): ObfBoard?
    suspend fun saveBoard(board: ObfBoard)

    /**
     * Persists many boards as a single batch. Repositories with bulk write
     * costs (e.g. full-store serialization) should override this to avoid
     * re-writing the whole store once per board on imports.
     */
    suspend fun saveBoards(boards: List<ObfBoard>) {
        boards.forEach { saveBoard(it) }
    }

    suspend fun listBoards(): List<ObfBoard>
    suspend fun deleteBoard(id: String)
}

interface BoardSetRepository {
    suspend fun getBoardSet(id: String): ObfBoardSet?
    suspend fun saveBoardSet(boardSet: ObfBoardSet)
    suspend fun listBoardSets(): List<ObfBoardSet>
    suspend fun deleteBoardSet(id: String)
}

interface PhraseRepository {
    suspend fun getAll(): List<Phrase>
    suspend fun add(phrase: Phrase): Phrase
    suspend fun update(phrase: Phrase): Phrase
    suspend fun delete(id: String)
    suspend fun move(fromIndex: Int, toIndex: Int)
}

interface CategoryRepository {
    suspend fun getAll(): List<CategoryItem>
    suspend fun add(category: CategoryItem): CategoryItem
    suspend fun update(category: CategoryItem): CategoryItem
    suspend fun delete(id: String)
    suspend fun move(fromIndex: Int, toIndex: Int)
}

interface SettingsRepository {
    suspend fun get(): Settings
    suspend fun update(settings: Settings): Settings
}

interface VoiceRepository {
    suspend fun getVoices(): List<Voice>
    suspend fun saveVoices(list: List<Voice>)
    suspend fun saveSelected(voice: Voice)
    suspend fun getSelected(): Voice?
}

interface SaidTextRepository {
    suspend fun add(item: SaidText): SaidText
    suspend fun list(): List<SaidText>
    suspend fun deleteAll()
    suspend fun addAll(items: List<SaidText>)
}

interface ConfigRepository {
    /** Internal credential-bearing API. Never expose its result to a UI or platform API. */
    suspend fun getSpeechConfig(): SpeechServiceConfig?
    suspend fun saveSpeechConfig(config: SpeechServiceConfig)
    suspend fun clearSpeechConfig()
    suspend fun getSpeechConfigStatus(): SpeechServiceConfigStatus {
        val config = getSpeechConfig()
        return SpeechServiceConfigStatus(
            endpoint = config?.endpoint.orEmpty(),
            credentialConfigured = !config?.subscriptionKey.isNullOrBlank()
        )
    }

    /** Internal credential-bearing API. Never expose its result to a UI or platform API. */
    suspend fun getGoogleSpeechConfig(): GoogleSpeechConfig?
    suspend fun saveGoogleSpeechConfig(config: GoogleSpeechConfig)
    suspend fun clearGoogleSpeechConfig()
    suspend fun getGoogleSpeechConfigStatus(): GoogleSpeechConfigStatus =
        GoogleSpeechConfigStatus(
            credentialConfigured = !getGoogleSpeechConfig()?.apiKey.isNullOrBlank(),
        )
}

enum class SpeechPlaybackStatus {
    IDLE,
    PREPARING,
    PLAYING,
    PAUSED,
    FAILED,
}

data class SpeechPlaybackState(
    val requestId: Long = 0,
    val status: SpeechPlaybackStatus = SpeechPlaybackStatus.IDLE,
    val error: String? = null,
)

interface SpeechService {
    suspend fun speak(text: String, voice: Voice? = null, pitch: Double? = null, rate: Double? = null)
    suspend fun speakWithCachePolicy(
        text: String,
        voice: Voice? = null,
        pitch: Double? = null,
        rate: Double? = null,
        cacheAudio: Boolean = true
    ) = speak(text, voice, pitch, rate)
    suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice? = null, pitch: Double? = null, rate: Double? = null)
    suspend fun speakSegmentsWithCachePolicy(
        segments: List<SpeechSegment>,
        voice: Voice? = null,
        pitch: Double? = null,
        rate: Double? = null,
        cacheAudio: Boolean = true
    ) = speakSegments(segments, voice, pitch, rate)
    /** Playback used by the Communication session, which records History after the request succeeds. */
    suspend fun speakWithoutHistory(
        text: String,
        voice: Voice? = null,
        pitch: Double? = null,
        rate: Double? = null,
        cacheAudio: Boolean = true,
    ) = speakWithCachePolicy(text, voice, pitch, rate, cacheAudio)
    /** Segmented counterpart to [speakWithoutHistory]. */
    suspend fun speakSegmentsWithoutHistory(
        segments: List<SpeechSegment>,
        voice: Voice? = null,
        pitch: Double? = null,
        rate: Double? = null,
        cacheAudio: Boolean = true,
    ) = speakSegmentsWithCachePolicy(segments, voice, pitch, rate, cacheAudio)
    /** Synthesize speech into the reusable cache without playing it or adding History. */
    suspend fun cacheSpeech(
        text: String,
        voice: Voice? = null,
        pitch: Double? = null,
        rate: Double? = null
    ): Boolean = false
    /** Retry cache work that was deferred while the device was offline. */
    suspend fun retryPendingSpeechCache() = Unit
    suspend fun speakRecordedAudio(audioFilePath: String, textForHistory: String? = null, voice: Voice? = null): Boolean = false
    suspend fun pause()
    suspend fun stop()
    suspend fun resume()
    fun isPlaying(): Boolean
    fun isPaused(): Boolean
    fun playbackState(): SpeechPlaybackState = when {
        isPaused() -> SpeechPlaybackState(status = SpeechPlaybackStatus.PAUSED)
        isPlaying() -> SpeechPlaybackState(status = SpeechPlaybackStatus.PLAYING)
        else -> SpeechPlaybackState()
    }
    suspend fun guessPronunciation(text: String, language: String = "en"): String? = null
}

/** Application hook used when a voice change requires board audio to be regenerated. */
interface BoardSpeechCache {
    suspend fun cacheAll()
}

interface UpdateService {
    suspend fun checkForUpdates(): UpdateInfo?
    suspend fun downloadUpdate(updateInfo: UpdateInfo): Result<String>
    suspend fun installUpdate(downloadPath: String): Result<Unit>
    fun getCurrentVersion(): AppVersion
    suspend fun getUpdateStatus(): UpdateStatus
    suspend fun setUpdateStatus(status: UpdateStatus)
}

/**
 * Prediction result containing word and letter suggestions.
 */
data class PredictionResult(
    val words: List<String> = emptyList(),
    val letters: List<Char> = emptyList()
)

/**
 * Service for predicting the next word or letter based on user's text history.
 * Uses a lightweight n-gram model trained on previously spoken text.
 */
interface TextPredictionService {
    /**
     * Train the model on the user's speech history.
     */
    suspend fun train(history: List<SaidText>)
    
    /**
     * Predict the next words and letters given the current input context.
     * @param context The current text being typed
     * @param maxWords Maximum number of word predictions to return
     * @param maxLetters Maximum number of letter predictions to return
     */
    suspend fun predict(context: String, maxWords: Int = 5, maxLetters: Int = 5): PredictionResult
    
    /**
     * Check if the model has been trained.
     */
    fun isTrained(): Boolean
}
