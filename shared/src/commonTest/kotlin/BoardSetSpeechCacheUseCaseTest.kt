import io.github.jdreioe.wingmate.application.BoardSetSpeechCacheUseCase
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfBoardSet
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryBoardSetRepository
import io.github.jdreioe.wingmate.infrastructure.InMemorySettingsRepository
import io.github.jdreioe.wingmate.infrastructure.InMemoryVoiceRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardSetSpeechCacheUseCaseTest {
    @Test
    fun cachesVocalizationsWithTheSelectedAzureVoiceAndWaitsWhileOffline() = runBlocking {
        val sets = InMemoryBoardSetRepository()
        val boards = InMemoryBoardRepository()
        val settings = InMemorySettingsRepository()
        val voices = InMemoryVoiceRepository()
        val speech = RecordingSpeechService()
        val cache = BoardSetSpeechCacheUseCase(sets, boards, settings, voices, speech)
        settings.update(Settings(ttsEngine = TtsEngine.AZURE_USER_RESOURCE, primaryLanguage = "en-US"))
        voices.saveSelected(Voice(name = "en-US-AvaNeural", selectedLanguage = "en-US"))
        boards.saveBoard(
            ObfBoard(
                format = "open-board-0.1",
                id = "home",
                buttons = listOf(ObfButton(id = "hello", label = "Hello", vocalization = "Hello there"))
            )
        )
        sets.saveBoardSet(ObfBoardSet("set", "Test", "home", listOf("home"), createdAt = 1, updatedAt = 1))

        cache.setOnline(false)
        cache.cacheAll()
        assertTrue(speech.cached.isEmpty())

        cache.setOnline(true)
        cache.cacheAll()
        assertEquals(listOf("Hello there"), speech.cached.map { it.first })
        assertEquals("en-US-AvaNeural", speech.cached.single().second?.name)
    }

    private class RecordingSpeechService : SpeechService {
        val cached = mutableListOf<Pair<String, Voice?>>()
        override suspend fun cacheSpeech(text: String, voice: Voice?, pitch: Double?, rate: Double?): Boolean {
            cached += text to voice
            return true
        }
        override suspend fun speak(text: String, voice: Voice?, pitch: Double?, rate: Double?) = Unit
        override suspend fun speakSegments(segments: List<SpeechSegment>, voice: Voice?, pitch: Double?, rate: Double?) = Unit
        override suspend fun pause() = Unit
        override suspend fun stop() = Unit
        override suspend fun resume() = Unit
        override fun isPlaying() = false
        override fun isPaused() = false
    }
}
