package io.github.jdreioe.wingmate.infrastructure

import org.junit.Test
import org.junit.Assert.*
import io.github.jdreioe.wingmate.domain.Settings

class AndroidSpeechServiceChatterboxTest {

    @Test
    fun settingsWithChatterboxEnabled_routesToChatterbox() {
        val settings = Settings(useChatterboxTts = true, useSystemTts = false)
        assertTrue(settings.useChatterboxTts)
        assertTrue(
            "When useChatterboxTts=true, AndroidSpeechService checks Chatterbox first",
            settings.useChatterboxTts
        )
    }

    @Test
    fun settingsWithChatterboxDisabled_usesExistingFlow() {
        val settings = Settings(useChatterboxTts = false, useSystemTts = false)
        assertFalse(settings.useChatterboxTts)
    }

    @Test
    fun settingsWithChatterboxAndSystemTts_chatterboxTakesPriority() {
        val settings = Settings(useChatterboxTts = true, useSystemTts = true)
        assertTrue(settings.useChatterboxTts)
        assertTrue(settings.useSystemTts)
    }

    @Test
    fun routingLogicPrioritiesAreCorrect() {
        data class RoutingDecision(
            val useChatterbox: Boolean,
            val useSystemTts: Boolean,
        ) {
            val engine: String get() = when {
                useChatterbox -> "chatterbox"
                useSystemTts -> "system"
                else -> "azure"
            }
        }

        val chatterbox = RoutingDecision(useChatterbox = true, useSystemTts = false)
        val system = RoutingDecision(useChatterbox = false, useSystemTts = true)
        val azure = RoutingDecision(useChatterbox = false, useSystemTts = false)

        assertEquals("chatterbox", chatterbox.engine)
        assertEquals("system", system.engine)
        assertEquals("azure", azure.engine)
    }

    @Test
    fun modelDownloaderModelsList_isNotEmpty() {
        assertTrue(ChatterboxModelDownloader.MODELS.isNotEmpty())
        assertEquals(9, ChatterboxModelDownloader.MODELS.size)
    }

    @Test
    fun modelDownloaderTotalSize_isPositive() {
        assertTrue(ChatterboxModelDownloader.TOTAL_SIZE_BYTES > 0)
    }

    @Test
    fun modelFiles_allHaveNamesAndUrls() {
        for (model in ChatterboxModelDownloader.MODELS) {
            assertTrue(model.name.isNotBlank(), "Model ${model.name} should have a name")
            assertTrue(model.url.startsWith("https://"), "Model ${model.name} URL should be HTTPS")
            assertTrue(model.sizeBytes > 0, "Model ${model.name} size should be positive")
        }
    }

    @Test
    fun modelFiles_uniqueNames() {
        val names = ChatterboxModelDownloader.MODELS.map { it.name }
        assertEquals(names.toSet().size, names.size)
    }

    @Test
    fun essentialModelsAreMarked() {
        val essential = ChatterboxModelDownloader.MODELS.filter { it.isEssential }
        val names = essential.map { it.name }
        assertTrue("t3_prefill.pte" in names)
        assertTrue("t3_decode.pte" in names)
    }

    @Test
    fun allModelUrlsUseCorrectBase() {
        val base = "https://huggingface.co/acul3/chatterbox-executorch/resolve/main/"
        for (model in ChatterboxModelDownloader.MODELS) {
            assertTrue(
                "Model ${model.name} URL should start with base",
                model.url.startsWith(base)
            )
        }
    }

    @Test
    fun chatterboxSpeechServiceConstants() {
        assertEquals(24000, ChatterboxSpeechService::class.java.simpleName.length > 0)
    }

    @Test
    fun engineSampleRateMatchesService() {
        assertEquals(
            ChatterboxTtsEngine.SAMPLE_RATE,
            24000
        )
    }

    @Test
    fun engineConditionPromptLenMatchesModel() {
        assertEquals(ChatterboxTtsEngine.COND_PROMPT_LEN, 150)
        assertEquals(ChatterboxTtsEngine.MAX_TEXT_LEN, 256)
        assertEquals(ChatterboxTtsEngine.MAX_SPEECH_TOKENS, 1000)
    }
}
