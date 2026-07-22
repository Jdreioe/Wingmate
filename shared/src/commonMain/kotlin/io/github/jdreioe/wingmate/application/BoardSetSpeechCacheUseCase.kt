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
import io.github.jdreioe.wingmate.domain.obf.resolveObfLocalizedString
import io.github.jdreioe.wingmate.domain.withLanguageOverride

/** Prewarms Azure audio for board fields without playing or recording them. */
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
        if (!online || !usesAzure()) return
        boardSetRepository.listBoardSets().forEach { cacheBoardSet(it.id) }
    }

    suspend fun cacheBoardSet(boardSetId: String) {
        if (!online || !usesAzure()) return
        val set = boardSetRepository.getBoardSet(boardSetId) ?: return
        val boards = set.boardIds.mapNotNull { boardRepository.getBoard(it) }
        cacheGraph(BoardSetGraph(set, boards))
    }

    suspend fun cacheGraph(graph: BoardSetGraph) {
        if (!online || !usesAzure()) return
        graph.boards.forEach { board ->
            board.buttons.forEach { button -> cacheField(board, button) }
        }
    }

    suspend fun cacheField(board: ObfBoard, button: ObfButton) {
        if (!online || !usesAzure()) return
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
        if (online && usesAzure()) speechService.retryPendingSpeechCache()
    }

    private suspend fun usesAzure(): Boolean = settingsRepository.get().ttsEngine != TtsEngine.SYSTEM
}
