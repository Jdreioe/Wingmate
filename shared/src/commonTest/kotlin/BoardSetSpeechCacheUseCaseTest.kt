import io.github.jdreioe.wingmate.application.BoardSetSpeechCacheUseCase
import io.github.jdreioe.wingmate.domain.Settings
import io.github.jdreioe.wingmate.domain.SpeechSegment
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.Voice
import io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.withPageSettingsOverrides
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
        sets.saveBoardSet(ObfBoardSet("set", "Test", "home", listOf("home"), createdAt = 1, updatedAt = 1, screenSettings = BoardSettingsOverrides(cacheSpeech = true)))

        cache.setOnline(false)
        cache.cacheAll()
        assertTrue(speech.cached.isEmpty())

        cache.setOnline(true)
        cache.cacheAll()
        assertEquals(listOf("Hello there"), speech.cached.map { it.first })
        assertEquals("en-US-AvaNeural", speech.cached.single().second?.name)
    }

    @Test
    fun doesNotGenerateTtsForAFieldWithRecordedAudio() = runBlocking {
        val settings = InMemorySettingsRepository().apply {
            update(Settings(ttsEngine = TtsEngine.AZURE_USER_RESOURCE))
        }
        val speech = RecordingSpeechService()
        val cache = BoardSetSpeechCacheUseCase(
            InMemoryBoardSetRepository(),
            InMemoryBoardRepository(),
            settings,
            InMemoryVoiceRepository(),
            speech
        )

        cache.cacheField(
            ObfBoard(format = "open-board-0.1", id = "home")
                .withPageSettingsOverrides(BoardSettingsOverrides(cacheSpeech = true)),
            ObfButton(id = "hello", label = "Hello", soundId = "recorded-hello")
        )

        assertTrue(speech.cached.isEmpty())
    }

    @Test
    fun cachesMathFieldsWithMathModeAndTheirSelectedLanguage() = runBlocking {
        val settings = InMemorySettingsRepository().apply {
            update(Settings(ttsEngine = TtsEngine.AZURE_USER_RESOURCE, primaryLanguage = "en-US"))
        }
        val voices = InMemoryVoiceRepository().apply {
            saveSelected(Voice(name = "en-US-AvaMultilingualNeural", selectedLanguage = "en-US"))
        }
        val speech = RecordingSpeechService()
        val cache = BoardSetSpeechCacheUseCase(
            InMemoryBoardSetRepository(),
            InMemoryBoardRepository(),
            settings,
            voices,
            speech
        )

        cache.cacheField(
            ObfBoard(format = "open-board-0.1", id = "home")
                .withPageSettingsOverrides(BoardSettingsOverrides(cacheSpeech = true)),
            ObfButton(id = "equation", label = "x + 2", locale = "da-DK").withMathMode(true)
        )

        val cachedVoice = speech.cached.single().second
        assertEquals("da-DK", cachedVoice?.selectedLanguage)
        assertTrue(cachedVoice?.mathMode == true)
    }

    @Test
    fun preloadingIsOptInAndPagePreferencesOverrideTheScreen() = runBlocking {
        val sets = InMemoryBoardSetRepository()
        val boards = InMemoryBoardRepository()
        val settings = InMemorySettingsRepository().apply {
            update(Settings(ttsEngine = TtsEngine.ELEVENLABS))
        }
        val speech = RecordingSpeechService()
        val cache = BoardSetSpeechCacheUseCase(sets, boards, settings, InMemoryVoiceRepository(), speech)
        val page = ObfBoard(
            format = "open-board-0.1", id = "home",
            buttons = listOf(ObfButton(id = "hello", label = "Hello")),
        )
        val screen = ObfBoardSet("set", "Test", "home", listOf("home"), createdAt = 1, updatedAt = 1)
        boards.saveBoard(page)
        sets.saveBoardSet(screen)

        cache.cacheAll()
        cache.cacheField(page, page.buttons.single())
        cache.retryPending()
        assertTrue(speech.cached.isEmpty())

        sets.saveBoardSet(screen.copy(screenSettings = BoardSettingsOverrides(cacheSpeech = true)))
        cache.cacheAll()
        assertEquals(1, speech.cached.size)
        speech.cached.clear()

        val disabledPage = page.withPageSettingsOverrides(BoardSettingsOverrides(cacheSpeech = false))
        boards.saveBoard(disabledPage)
        cache.cacheAll()
        cache.cacheField(disabledPage, disabledPage.buttons.single())
        cache.retryPending()
        assertTrue(speech.cached.isEmpty())

        sets.saveBoardSet(screen)
        boards.saveBoard(page.withPageSettingsOverrides(BoardSettingsOverrides(cacheSpeech = true)))
        cache.cacheAll()
        assertEquals(1, speech.cached.size)
    }

    @Test
    fun disablingPreloadingStopsTheRestOfAnOngoingPass() = runBlocking {
        val sets = InMemoryBoardSetRepository()
        val boards = InMemoryBoardRepository()
        val settings = InMemorySettingsRepository().apply {
            update(Settings(ttsEngine = TtsEngine.ELEVENLABS))
        }
        val page = ObfBoard(
            format = "open-board-0.1", id = "home",
            buttons = listOf(ObfButton(id = "one", label = "One"), ObfButton(id = "two", label = "Two")),
        ).withPageSettingsOverrides(BoardSettingsOverrides(cacheSpeech = true))
        boards.saveBoard(page)
        sets.saveBoardSet(ObfBoardSet("set", "Test", "home", listOf("home"), createdAt = 1, updatedAt = 1))
        val speech = RecordingSpeechService().apply {
            onCache = {
                boards.saveBoard(page.withPageSettingsOverrides(BoardSettingsOverrides(cacheSpeech = false)))
            }
        }
        val cache = BoardSetSpeechCacheUseCase(sets, boards, settings, InMemoryVoiceRepository(), speech)

        cache.cacheAll()
        cache.retryPending()

        assertEquals(listOf("One"), speech.cached.map { it.first })
    }

    private class RecordingSpeechService : SpeechService {
        val cached = mutableListOf<Pair<String, Voice?>>()
        var onCache: (suspend () -> Unit)? = null
        override suspend fun cacheSpeech(text: String, voice: Voice?, pitch: Double?, rate: Double?): Boolean {
            cached += text to voice
            onCache?.invoke()
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
