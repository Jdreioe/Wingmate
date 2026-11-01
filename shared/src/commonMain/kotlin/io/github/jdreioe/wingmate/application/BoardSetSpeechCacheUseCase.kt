package io.github.jdreioe.wingmate.application

import io.github.jdreioe.wingmate.domain.BoardRepository
import io.github.jdreioe.wingmate.domain.BoardSpeechCache
import io.github.jdreioe.wingmate.domain.BoardSetRepository
import io.github.jdreioe.wingmate.domain.SettingsRepository
import io.github.jdreioe.wingmate.domain.SpeechService
import io.github.jdreioe.wingmate.domain.TtsEngine
import io.github.jdreioe.wingmate.domain.VoiceRepository
import io.github.jdreioe.wingmate.domain.obf.BoardSetGraph
import io.github.jdreioe.wingmate.domain.obf.ObfBoard
import io.github.jdreioe.wingmate.domain.obf.ObfButton
import io.github.jdreioe.wingmate.domain.obf.BoardSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.pageSettingsOverrides
import io.github.jdreioe.wingmate.domain.obf.resolveObfLocalizedString
import io.github.jdreioe.wingmate.domain.withLanguageOverride

/** Preloads cloud audio only for Pages that opt in, without playing or recording it. */
class BoardSetSpeechCacheUseCase(
    private val boardSetRepository: BoardSetRepository,
    private val boardRepository: BoardRepository,
    private val settingsRepository: SettingsRepository,
    private val voiceRepository: VoiceRepository,
    private val speechService: SpeechService,
) : BoardSpeechCache {
    private var online = true

    fun setOnline(value: Boolean) {
        online = value
    }

    override suspend fun cacheAll() {
        if (!online || !usesCloudTts()) return
        boardSetRepository.listBoardSets().forEach { cacheBoardSet(it.id) }
    }

    suspend fun cacheBoardSet(boardSetId: String) {
        if (!online || !usesCloudTts()) return
        val set = boardSetRepository.getBoardSet(boardSetId) ?: return
        val boards = set.boardIds.mapNotNull { boardRepository.getBoard(it) }
        cacheGraph(BoardSetGraph(set, boards))
    }

    suspend fun cacheGraph(graph: BoardSetGraph) {
        if (!online || !usesCloudTts()) return
        for (board in graph.boards) {
            for (button in board.buttons) {
                // Re-read saved preferences so disabling preloading also stops an ongoing pass.
                val currentSet = boardSetRepository.getBoardSet(graph.boardSet.id) ?: return
                val currentBoard = boardRepository.getBoard(board.id) ?: continue
                val currentButton = currentBoard.buttons.firstOrNull { it.id == button.id } ?: continue
                cacheField(currentBoard, currentButton, currentSet.screenSettings)
            }
        }
    }

    suspend fun cacheField(board: ObfBoard, button: ObfButton) {
        val screen = boardSetRepository.listBoardSets().firstOrNull { board.id in it.boardIds }
        cacheField(board, button, screen?.screenSettings ?: BoardSettingsOverrides())
    }

    private suspend fun cacheField(board: ObfBoard, button: ObfButton, screen: BoardSettingsOverrides) {
        if (!(board.pageSettingsOverrides().cacheSpeech ?: screen.cacheSpeech ?: false)) return
        if (!online || !usesCloudTts()) return
        if (!button.soundId.isNullOrBlank()) return
        val settings = settingsRepository.get()
        val locale = button.locale ?: board.locale ?: settings.primaryLanguage
        val rawText = button.vocalization?.takeIf(String::isNotBlank)
            ?: button.label?.takeIf(String::isNotBlank)
            ?: return
        val text = resolveObfLocalizedString(board.strings, locale, rawText)?.trim().orEmpty()
        if (text.isEmpty()) return
        val voice = voiceRepository.getSelected().withLanguageOverride(locale)
            ?.copy(mathMode = button.mathMode)
        speechService.cacheSpeech(text, voice, voice?.pitch, voice?.rate)
    }

    suspend fun retryPending() {
        cacheAll()
    }

    private suspend fun usesCloudTts(): Boolean = settingsRepository.get().ttsEngine != TtsEngine.SYSTEM
}
