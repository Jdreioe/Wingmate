package io.github.jdreioe.wingmate.infrastructure

import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.chatterbox.TtsEngine
import io.github.jdreioe.wingmate.domain.normalizedTtsEngineSettings
import io.github.jdreioe.wingmate.domain.withTtsEngine
import io.github.jdreioe.wingmate.infrastructure.chatterbox.OfficialModelRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSpeechServiceChatterboxTest {
    @Test
    fun legacyChatterboxFlagMigratesToCanonicalEngine() {
        val settings = Settings(useChatterboxTts = true, useSystemTts = true).normalizedTtsEngineSettings()
        assertEquals(TtsEngine.Chatterbox, settings.ttsEngine)
        assertTrue(settings.useChatterboxTts)
        assertFalse(settings.useSystemTts)
    }

    @Test
    fun canonicalSelectionsKeepLegacyFlagsConsistent() {
        val system = Settings().withTtsEngine(TtsEngine.System)
        val azure = system.withTtsEngine(TtsEngine.Azure)
        assertTrue(system.useSystemTts)
        assertFalse(system.useChatterboxTts)
        assertFalse(azure.useSystemTts)
        assertFalse(azure.useChatterboxTts)
    }

    @Test
    fun registryContainsOnlyPinnedQ4Package() {
        assertEquals(listOf(OfficialModelRegistry.Q4_MODEL_ID), OfficialModelRegistry.models.map { it.id })
        assertEquals(11, ChatterboxModelDownloader.MODELS.size)
        assertEquals(1_555_820_320L, ChatterboxModelDownloader.TOTAL_SIZE_BYTES)
        assertEquals(ChatterboxModelDownloader.TOTAL_SIZE_BYTES, ChatterboxModelDownloader.MODELS.sumOf { it.sizeBytes })
        assertTrue(ChatterboxModelDownloader.MODELS.none { it.relativePath.contains("hifigan") })
        assertTrue(ChatterboxModelDownloader.MODELS.all { it.url.contains(OfficialModelRegistry.REVISION) })
        assertTrue(ChatterboxModelDownloader.MODELS.all { it.sha256.length == 64 })
    }
}
