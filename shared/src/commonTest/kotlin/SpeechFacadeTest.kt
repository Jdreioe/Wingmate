import io.github.jdreioe.wingmate.application.NoopFeatureUsageReporter
import io.github.jdreioe.wingmate.application.SettingsUseCase
import io.github.jdreioe.wingmate.application.SpeechFacade
import io.github.jdreioe.wingmate.application.VoiceUseCase
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.VoiceProvider
import io.github.jdreioe.wingmate.infrastructure.AzureVoiceCatalog
import io.github.jdreioe.wingmate.infrastructure.GoogleVoiceCatalog
import io.github.jdreioe.wingmate.infrastructure.InMemoryConfigRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySettingsRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryVoiceRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpeechFacadeTest {
    @Test
    fun selectingVoiceAlignsPrimaryLanguageWhenItDiffers() = runBlocking {
        val settings = InMemorySettingsRepository()
        val facade = facade(settings = settings)
        val voice = Voice(name = "Dansk", primaryLanguage = "da", selectedLanguage = "da")

        facade.selectVoiceAndMaybeUpdatePrimary(voice)

        val persisted = settings.get()
        assertEquals("da", persisted.primaryLanguage)
        assertEquals(voice, facade.selectedVoice())
    }

    @Test
    fun selectingVoiceWithoutLanguageKeepsExistingPrimary() = runBlocking {
        val settings = InMemorySettingsRepository()
        val facade = facade(settings = settings)
        val voice = Voice(name = "Silent", primaryLanguage = null, selectedLanguage = "")

        facade.selectVoiceAndMaybeUpdatePrimary(voice)

        assertEquals("en-US", settings.get().primaryLanguage)
        assertEquals(voice, facade.selectedVoice())
    }

    @Test
    fun updateSelectedVoiceLanguageUpdatesVoiceAndAlignsPrimary() = runBlocking {
        val settings = InMemorySettingsRepository()
        val facade = facade(settings = settings)
        val voice = Voice(name = "Danish", primaryLanguage = "en", selectedLanguage = "en")
        facade.selectVoiceAndMaybeUpdatePrimary(voice)

        facade.updateSelectedVoiceLanguage("da")

        val persisted = facade.selectedVoice()
        assertEquals("da", persisted?.selectedLanguage)
        assertEquals("da", settings.get().primaryLanguage)
    }

    @Test
    fun listVoicesOnlyReturnsTheSelectedProvidersCatalog() = runBlocking {
        val settings = InMemorySettingsRepository().apply {
            update(get().copy(ttsEngine = TtsEngine.GOOGLE_CLOUD))
        }
        val voices = InMemoryVoiceRepository().apply {
            saveVoices(
                listOf(
                    Voice(name = "azure", provider = VoiceProvider.AZURE),
                    Voice(name = "google", provider = VoiceProvider.GOOGLE),
                ),
            )
        }
        val facade = facade(settings = settings, voices = voices)

        assertEquals(listOf("google"), facade.listVoices().map { it.name })
    }

    @Test
    fun savingAzureSpeechConfigPersistsEndpointAndSwitchesToAzureEngine() = runBlocking {
        val settings = InMemorySettingsRepository()
        val config = InMemoryConfigRepository()
        val facade = facade(settings = settings, config = config)

        facade.saveAzureSpeechConfig("  https://westeurope.tts.speech.microsoft.com  ", " secret-key ")

        assertTrue(storedConfigHasKey(config))
        assertEquals("https://westeurope.tts.speech.microsoft.com", storedEndpoint(config))
        assertEquals(TtsEngine.AZURE_USER_RESOURCE, settings.get().ttsEngine)
    }

    @Test
    fun getSpeechConfigExposesConfiguredFlagWithoutSubscriptionKey() = runBlocking {
        val config = InMemoryConfigRepository()
        val facade = facade(config = config)

        facade.saveAzureSpeechConfig("https://westeurope.tts.speech.microsoft.com", "secret-key")

        val status = facade.getSpeechConfig()
        assertTrue(status.credentialConfigured)
        assertTrue(status.endpoint.contains("westeurope"))
    }

    @Test
    fun googleConfigStatusIsRedactedAndClearable() = runBlocking {
        val config = InMemoryConfigRepository()
        val facade = facade(config = config)

        facade.saveValidatedGoogleSpeechConfig("google-secret") { listOf(Voice(name = "en-US-Neural2-A")) }

        assertTrue(facade.getGoogleSpeechConfig().credentialConfigured)

        facade.clearGoogleSpeechConfig()
        assertEquals(null, config.getGoogleSpeechConfig())
    }

    @Test
    fun validatedGoogleSetupStoresKeyAndSwitchesOnlyAfterVoicesLoad() = runBlocking {
        val settings = InMemorySettingsRepository()
        val config = InMemoryConfigRepository()
        val facade = facade(settings = settings, config = config)
        val voice = Voice(name = "en-US-Neural2-A", selectedLanguage = "en-US")

        val voices = facade.saveValidatedGoogleSpeechConfig(" google-secret ") { listOf(voice) }

        assertEquals(listOf(voice), voices)
        assertEquals("google-secret", config.getGoogleSpeechConfig()?.apiKey)
        assertEquals(TtsEngine.GOOGLE_CLOUD, settings.get().ttsEngine)
    }

    @Test
    fun failedGoogleReplacementRestoresCredentialAndEngine() = runBlocking {
        val settings = InMemorySettingsRepository().apply {
            update(get().copy(ttsEngine = TtsEngine.SYSTEM))
        }
        val config = InMemoryConfigRepository().apply {
            saveGoogleSpeechConfig(io.github.jdreioe.wingmate.domain.GoogleSpeechConfig("working-key"))
        }
        val facade = facade(settings = settings, config = config)

        assertFailsWith<IllegalStateException> {
            facade.saveValidatedGoogleSpeechConfig("bad-key") { emptyList() }
        }

        assertEquals("working-key", config.getGoogleSpeechConfig()?.apiKey)
        assertEquals(TtsEngine.SYSTEM, settings.get().ttsEngine)
    }

    private fun storedConfigHasKey(config: InMemoryConfigRepository): Boolean =
        runBlocking { !config.getSpeechConfig()?.subscriptionKey.isNullOrBlank() }

    private fun storedEndpoint(config: InMemoryConfigRepository): String =
        runBlocking { config.getSpeechConfig()?.endpoint.orEmpty() }

    private fun facade(
        settings: InMemorySettingsRepository = InMemorySettingsRepository(),
        config: InMemoryConfigRepository = InMemoryConfigRepository(),
        voices: InMemoryVoiceRepository = InMemoryVoiceRepository(),
    ): SpeechFacade {
        val voiceUseCase = VoiceUseCase(
            repo = voices,
            azure = AzureVoiceCatalog(config),
            google = GoogleVoiceCatalog(config),
            configRepo = config,
            featureUsageReporter = NoopFeatureUsageReporter(),
        )
        return SpeechFacade(
            voiceUseCase = voiceUseCase,
            settingsUseCase = SettingsUseCase(settings),
            configRepository = config,
        )
    }
}
