package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.*
import kotlin.random.Random
import kotlin.time.Clock

class InMemoryPhraseRepository : PhraseRepository {
    private val store = mutableListOf<Phrase>()
    override suspend fun getAll(): List<Phrase> {
        return store.toList()
    }
    override suspend fun add(phrase: Phrase): Phrase {
        val p = phrase.copy(id = phrase.id.ifBlank { Random.nextInt().toString() }, createdAt = if (phrase.createdAt == 0L) Clock.System.now().toEpochMilliseconds() else phrase.createdAt)
        store.add(p)
        return p
    }
    override suspend fun update(phrase: Phrase): Phrase {
        val idx = store.indexOfFirst { it.id == phrase.id }
        if (idx >= 0) store[idx] = phrase
        return phrase
    }

    override suspend fun delete(id: String) {
        store.removeAll { it.id == id }
    }
    override suspend fun move(fromIndex: Int, toIndex: Int) {
        if (fromIndex < 0 || fromIndex >= store.size) return
        val item = store.removeAt(fromIndex)
        val insertIndex = toIndex.coerceIn(0, store.size)
        store.add(insertIndex, item)
    }
}

class InMemorySettingsRepository : SettingsRepository {
    private var settings = Settings()
    override suspend fun get(): Settings {
        return settings
    }
    override suspend fun update(settings: Settings): Settings {
        this.settings = settings
        return settings
    }
}

class InMemoryVoiceRepository : VoiceRepository {
    private var selected: Voice? = null
    private val voices = mutableListOf<Voice>()
    override suspend fun getVoices(): List<Voice> {
        return voices.toList()
    }
    override suspend fun saveVoices(list: List<Voice>) {
        voices.clear()
        voices.addAll(list)
    }
    override suspend fun saveSelected(voice: Voice) {
        selected = voice
    }
    override suspend fun getSelected(): Voice? {
        return selected
    }
}

class InMemorySaidTextRepository : SaidTextRepository {
    private val items = mutableListOf<SaidText>()
    override suspend fun add(item: SaidText): SaidText {
        items.add(item)
        return item
    }
    override suspend fun list(): List<SaidText> {
        return items.toList()
    }
    override suspend fun deleteAll() {
        items.clear()
    }
    override suspend fun addAll(items: List<SaidText>) {
        this.items.addAll(items)
    }
}

class InMemoryConfigRepository : ConfigRepository {
    private var cfg: SpeechServiceConfig? = null
    private var googleCfg: GoogleSpeechConfig? = null
    override suspend fun getSpeechConfig(): SpeechServiceConfig? {
        return cfg
    }
    override suspend fun saveSpeechConfig(config: SpeechServiceConfig) {
        cfg = config.validatedForStorage()
    }
    override suspend fun clearSpeechConfig() {
        cfg = null
    }
    override suspend fun getGoogleSpeechConfig(): GoogleSpeechConfig? {
        return googleCfg
    }
    override suspend fun saveGoogleSpeechConfig(config: GoogleSpeechConfig) {
        googleCfg = config.copy(apiKey = config.apiKey.trim()).also {
            require(it.apiKey.isNotEmpty()) { "Google Cloud API key is required" }
        }
    }
    override suspend fun clearGoogleSpeechConfig() {
        googleCfg = null
    }
}

class NoopSpeechService : SpeechService {
    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) { /* no-op */ }
    override suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) { /* no-op */ }
    override suspend fun pause() { /* no-op */ }
    override suspend fun stop() { /* no-op */ }
    override suspend fun resume() { /* no-op */ }
    override fun isPlaying(): Boolean = false
    override fun isPaused(): Boolean = false
    override suspend fun guessPronunciation(text: String, language: String): String? = null
}
