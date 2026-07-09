package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.*
import io.github.jdreioe.wingmate.domain.chatterbox.ChatterboxModel
import io.github.jdreioe.wingmate.domain.chatterbox.ClonedVoiceProfile
import io.github.jdreioe.wingmate.domain.chatterbox.ModelRepository
import io.github.jdreioe.wingmate.domain.chatterbox.VoiceProfileRepository
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Clock

class InMemoryPhraseRepository : PhraseRepository {
    private val store = mutableListOf<Phrase>()
    override suspend fun getAll(): List<Phrase> {
        delay(50)
        return store.toList()
    }
    override suspend fun add(phrase: Phrase): Phrase {
        delay(50)
        val p = phrase.copy(id = phrase.id.ifBlank { Random.nextInt().toString() }, createdAt = if (phrase.createdAt == 0L) Clock.System.now().toEpochMilliseconds() else phrase.createdAt)
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
    override suspend fun saveVoices(list: List<Voice>) {
        delay(10)
        voices.clear()
        voices.addAll(list)
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
    override suspend fun deleteAll() {
        delay(10)
        items.clear()
    }
    override suspend fun addAll(list: List<SaidText>) {
        delay(10)
        items.addAll(list)
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
    override suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) { /* no-op */ }
    override suspend fun pause() { /* no-op */ }
    override suspend fun stop() { /* no-op */ }
    override suspend fun resume() { /* no-op */ }
    override fun isPlaying(): Boolean = false
    override fun isPaused(): Boolean = false
    override suspend fun guessPronunciation(text: String, language: String): String? = null
}

class InMemoryModelRepository : ModelRepository {
    private val models = mutableListOf<ChatterboxModel>()
    private var active: ChatterboxModel? = null

    override suspend fun list(): List<ChatterboxModel> {
        delay(10)
        return models.toList()
    }

    override suspend fun get(id: String): ChatterboxModel? {
        delay(10)
        return models.find { it.id == id }
    }

    override suspend fun save(model: ChatterboxModel) {
        delay(10)
        val idx = models.indexOfFirst { it.id == model.id }
        if (idx >= 0) models[idx] = model else models.add(model)
    }

    override suspend fun delete(id: String) {
        delay(10)
        models.removeAll { it.id == id }
        if (active?.id == id) active = null
    }

    override suspend fun getActive(): ChatterboxModel? {
        delay(10)
        return active
    }

    override suspend fun setActive(model: ChatterboxModel) {
        delay(10)
        active = model
    }
}

class InMemoryVoiceProfileRepository : VoiceProfileRepository {
    private val profiles = mutableListOf<ClonedVoiceProfile>()
    private var active: ClonedVoiceProfile? = null

    override suspend fun list(): List<ClonedVoiceProfile> {
        delay(10)
        return profiles.toList()
    }

    override suspend fun get(id: String): ClonedVoiceProfile? {
        delay(10)
        return profiles.find { it.id == id }
    }

    override suspend fun save(profile: ClonedVoiceProfile) {
        delay(10)
        val idx = profiles.indexOfFirst { it.id == profile.id }
        if (idx >= 0) profiles[idx] = profile else profiles.add(profile)
    }

    override suspend fun delete(id: String) {
        delay(10)
        profiles.removeAll { it.id == id }
        if (active?.id == id) active = null
    }

    override suspend fun getActive(): ClonedVoiceProfile? {
        delay(10)
        return active
    }

    override suspend fun setActive(profile: ClonedVoiceProfile) {
        delay(10)
        active = profile
    }
}
