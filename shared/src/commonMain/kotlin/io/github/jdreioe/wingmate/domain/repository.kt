package io.github.jdreioe.wingmate.domain

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
}

interface ConfigRepository {
    suspend fun getSpeechConfig(): SpeechServiceConfig?
    suspend fun saveSpeechConfig(config: SpeechServiceConfig)
}

interface SpeechService {
    suspend fun speak(text: String, voice: Voice? = null, pitch: Double? = null, rate: Double? = null)
    suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice? = null, pitch: Double? = null, rate: Double? = null)
    suspend fun pause()
    suspend fun stop()
    suspend fun resume()
    fun isPlaying(): Boolean
    fun isPaused(): Boolean
    suspend fun guessPronunciation(text: String, language: String = "en"): String? = null
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
