package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.AzureVoiceCatalogPort
import io.github.jdreioe.wingmate.domain.ElevenLabsVoiceCatalogPort
import io.github.jdreioe.wingmate.domain.GoogleVoiceCatalogPort
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceProvider
import io.github.jdreioe.wingmate.domain.VoiceRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceUseCaseTest {
    @Test
    fun `refreshing cloud catalogs saves voices and falls back to cached voices when empty`() = runTest {
        val repo = FakeVoiceRepository()
        val azureVoice = Voice(name = "en-US-JennyNeural", primaryLanguage = "en-US", provider = VoiceProvider.AZURE)
        val googleVoice = Voice(name = "en-US-Chirp3-HD-Aoede", primaryLanguage = "en-US", provider = VoiceProvider.GOOGLE)
        val elevenLabsVoice = Voice(name = "el-voice-1", primaryLanguage = "en-US", provider = VoiceProvider.ELEVENLABS)

        var azureList = listOf(azureVoice)
        val useCase = VoiceUseCase(
            repo = repo,
            azure = object : AzureVoiceCatalogPort {
                override suspend fun list(): List<Voice> = azureList
            },
            google = object : GoogleVoiceCatalogPort {
                override suspend fun list(): List<Voice> = listOf(googleVoice)
                override suspend fun load(): List<Voice> = listOf(googleVoice)
            },
            elevenLabs = object : ElevenLabsVoiceCatalogPort {
                override suspend fun list(): List<Voice> = listOf(elevenLabsVoice)
                override suspend fun load(): List<Voice> = listOf(elevenLabsVoice)
            },
            featureUsageReporter = NoopFeatureUsageReporter(),
        )

        assertEquals(listOf(azureVoice), useCase.refreshFromAzure())

        azureList = emptyList()
        assertEquals(listOf(azureVoice), useCase.refreshFromAzure())

        assertEquals(listOf(googleVoice), useCase.loadGoogleCatalog())
        assertEquals(listOf(googleVoice), useCase.refreshFromGoogle())
        assertEquals(listOf(elevenLabsVoice), useCase.loadElevenLabsCatalog())
        assertEquals(listOf(elevenLabsVoice), useCase.refreshFromElevenLabs())
        assertEquals(listOf(elevenLabsVoice), useCase.listForEngine(TtsEngine.ELEVENLABS))
    }

    private class FakeVoiceRepository : VoiceRepository {
        private var voices: List<Voice> = emptyList()
        private var selected: Voice? = null

        override suspend fun getVoices(): List<Voice> = voices
        override suspend fun saveVoices(list: List<Voice>) {
            voices = list
        }
        override suspend fun saveSelected(voice: Voice) {
            selected = voice
        }
        override suspend fun getSelected(): Voice? = selected
    }
}
