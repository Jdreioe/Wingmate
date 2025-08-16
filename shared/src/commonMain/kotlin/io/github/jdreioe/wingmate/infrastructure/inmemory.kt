package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.*
import kotlinx.coroutines.delay
import kotlin.random.Random

class InMemoryPhraseRepository : PhraseRepository {
    private val store = mutableListOf<Phrase>()
    override suspend fun getAll(): List<Phrase> {
        delay(50)
        return store.toList()
    }
    override suspend fun add(phrase: Phrase): Phrase {
        delay(50)
        val p = phrase.copy(id = phrase.id.ifBlank { Random.nextInt().toString() }, createdAt = if (phrase.createdAt == 0L) System.currentTimeMillis() else phrase.createdAt)
        store.add(p)
        return p
    }
    override suspend fun update(phrase: Phrase): Phrase {
        delay(20)
        val idx = store.indexOfFirst { it.id == phrase.id }
        if (idx >= 0) store[idx] = phrase
        return phrase
    }

    override suspend fun delete(id: String) {
        delay(20)
        store.removeAll { it.id == id }
    }
    override suspend fun move(fromIndex: Int, toIndex: Int) {
        delay(10)
        if (fromIndex < 0 || fromIndex >= store.size) return
        val item = store.removeAt(fromIndex)
        val insertIndex = toIndex.coerceIn(0, store.size)
        store.add(insertIndex, item)
    }
}

class InMemorySettingsRepository : SettingsRepository {
    private var settings = Settings()
    override suspend fun get(): Settings {
        delay(50)
        return settings
    }
    override suspend fun update(settings: Settings): Settings {
        delay(50)
        this.settings = settings
        return settings
    }
}

class InMemoryVoiceRepository : VoiceRepository {
    private var selected: Voice? = null
    private val voices = mutableListOf<Voice>()
    override suspend fun getVoices(): List<Voice> {
        delay(10)
        return voices.toList()
    }
    override suspend fun saveSelected(voice: Voice) {
        delay(10)
        selected = voice
    }
    override suspend fun getSelected(): Voice? {
        delay(10)
        return selected
    }
}

class InMemorySaidTextRepository : SaidTextRepository {
    private val items = mutableListOf<SaidText>()
    override suspend fun add(item: SaidText): SaidText {
        delay(10)
        items.add(item)
        return item
    }
    override suspend fun list(): List<SaidText> {
        delay(10)
        return items.toList()
    }
}

class InMemoryConfigRepository : ConfigRepository {
    private var cfg: SpeechServiceConfig? = null
    override suspend fun getSpeechConfig(): SpeechServiceConfig? {
        delay(10)
        return cfg
    }
    override suspend fun saveSpeechConfig(config: SpeechServiceConfig) {
        delay(10)
        cfg = config
    }
}

class NoopSpeechService : SpeechService {
    override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) { /* no-op */ }
    override suspend fun pause() { /* no-op */ }
    override suspend fun stop() { /* no-op */ }
}
