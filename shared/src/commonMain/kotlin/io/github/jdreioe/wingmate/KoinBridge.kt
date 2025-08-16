package io.github.jdreioe.wingmate

import io.github.jdreioe.wingmate.application.PhraseBloc
import io.github.jdreioe.wingmate.application.PhraseUseCase
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.Phrase
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.Voice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext

class KoinBridge {
    private fun koin() = GlobalContext.get()

    fun phraseBloc(): PhraseBloc = koin().get()

    private fun phraseUseCase(): PhraseUseCase = koin().get()
    private fun settingsUseCase(): SettingsUseCase = koin().get()
    private fun voiceUseCase(): VoiceUseCase = koin().get()
    private fun speech(): SpeechService = koin().get()

    // Suspend wrappers useful from Swift (bridged as completion handlers)
    suspend fun listPhrases(): List<Phrase> = withContext(Dispatchers.Default) { phraseUseCase().list() }
    suspend fun addPhrase(phrase: Phrase): Phrase = withContext(Dispatchers.Default) { phraseUseCase().add(phrase) }
    suspend fun updatePhrase(phrase: Phrase): Phrase = withContext(Dispatchers.Default) { phraseUseCase().update(phrase) }
    suspend fun deletePhrase(id: String) = withContext(Dispatchers.Default) { phraseUseCase().delete(id) }
    suspend fun movePhrase(fromIndex: Int, toIndex: Int) = withContext(Dispatchers.Default) { phraseUseCase().move(fromIndex, toIndex) }

    suspend fun getSettings(): Settings = withContext(Dispatchers.Default) { settingsUseCase().get() }
    suspend fun updateSettings(settings: Settings): Settings = withContext(Dispatchers.Default) { settingsUseCase().update(settings) }

    suspend fun listVoices(): List<Voice> = withContext(Dispatchers.Default) { voiceUseCase().list() }
    suspend fun selectedVoice(): Voice? = withContext(Dispatchers.Default) { voiceUseCase().selected() }
    suspend fun selectVoice(voice: Voice) = withContext(Dispatchers.Default) { voiceUseCase().select(voice) }

    suspend fun speak(text: String) = withContext(Dispatchers.Default) {
        val v = runCatching { voiceUseCase().selected() }.getOrNull()
        speech().speak(text, v, v?.pitch, v?.rate)
    }
}
