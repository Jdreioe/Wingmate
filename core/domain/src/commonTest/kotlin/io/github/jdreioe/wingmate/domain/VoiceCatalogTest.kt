package io.github.jdreioe.wingmate.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VoiceCatalogTest {
    @Test
    fun catalogsAreFilteredToTheSelectedCloudProvider() {
        val azure = Voice(name = "en-US-AvaMultilingualNeural", provider = VoiceProvider.AZURE)
        val google = Voice(name = "en-US-Neural2-A", provider = VoiceProvider.GOOGLE)
        val legacyGoogle = Voice(name = "da-DK-Wavenet-A")
        val voices = listOf(azure, google, legacyGoogle)

        assertEquals(listOf(azure), voices.forTtsEngine(TtsEngine.AZURE_USER_RESOURCE))
        assertEquals(listOf(google, legacyGoogle), voices.forTtsEngine(TtsEngine.GOOGLE_CLOUD))
        assertTrue(voices.forTtsEngine(TtsEngine.SYSTEM).isEmpty())
    }

    @Test
    fun chirpSpeakersProduceOneStableEntryPerGeminiModel() {
        val chirp = Voice(
            name = "en-US-Chirp3-HD-Kore",
            primaryLanguage = "en-US",
            provider = VoiceProvider.GOOGLE,
            googleModel = GoogleVoiceModel.CHIRP_3_HD,
        )

        val danishChirp = chirp.copy(
            name = "da-DK-Chirp3-HD-Kore",
            primaryLanguage = "da-DK",
        )
        val voices = listOf(chirp, danishChirp).toGoogleVoiceCatalog()

        assertEquals(5, voices.size)
        assertEquals(
            GoogleVoiceModel.entries.filter { it.apiModelName != null },
            voices.take(4).map { it.googleModel },
        )
        assertTrue(voices.take(4).all { it.providerVoiceName == "Kore" && it.displayName == "Kore" })
        assertEquals(listOf("da-DK", "en-US"), voices.last().supportedLanguages)
        assertEquals("Kore", voices.last().displayName)
        assertEquals("", voices.last().selectedLanguage)
    }

    @Test
    fun legacySpeakersHaveShortLabelsAndAggregateLocales() {
        val voices = listOf(
            Voice(name = "da-DK-Wavenet-F", primaryLanguage = "da-DK", provider = VoiceProvider.GOOGLE),
            Voice(name = "en-US-Wavenet-F", primaryLanguage = "en-US", provider = VoiceProvider.GOOGLE),
        ).toGoogleVoiceCatalog()

        assertEquals(1, voices.size)
        assertEquals("WaveNet F", voices.single().displayName)
        assertEquals(listOf("da-DK", "en-US"), voices.single().supportedLanguages)
        assertEquals("en-US", voices.single().withPreferredSupportedLanguage("en-US").selectedLanguage)
    }
}
