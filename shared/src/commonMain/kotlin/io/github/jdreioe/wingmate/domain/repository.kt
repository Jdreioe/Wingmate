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
    suspend fun pause()
    suspend fun stop()
}
